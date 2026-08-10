import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP-слой встроенного шлюза — com.sun.net.httpserver из JDK, без фреймворков.
 *
 * Публичное:  GET  /healthz  — жив ли сервис
 *             POST /v1/chat  — {"prompt": "...", "mode": "block"|"mask",
 *                              "system": "..."?} → ответ LLM + вердикты стражей
 *                              + usage/стоимость. Поле system опционально:
 *                              кастомный системный промпт на вызов (генерация vs
 *                              security review); без него — GatewayPrompt.SYSTEM.
 *             GET  /v1/audit — последние записи аудита + итоги стоимости сессии
 *
 * Порядок обороны на /v1/chat: 405 (метод) → 429 (rate limit по IP) →
 * 413 (Content-Length больше потолка — ДО чтения тела, чтение через readNBytes
 * с потолком) → 400 (парсинг/валидация тела). Авторизации в этот день нет.
 * Формат ошибок: {"error": {"code": "...", "message": "..."}}.
 */
class HttpApi(
    private val inputGuard: InputGuard,
    private val outputGuard: OutputGuard,
    private val client: DeepSeekClient?,
    private val audit: AuditLog,
    private val costs: CostTracker,
    private val limiter: RateLimiter = RateLimiter(Config.rateLimitPerMin()),
) {
    private val json = Json { encodeDefaults = true }
    private val startedAt = System.currentTimeMillis()
    private val served = AtomicLong(0)

    fun start(): HttpServer {
        // Внутренний шлюз — ВСЕГДА на loopback: наружу его не выставляем,
        // публично торчит только AgentApi (Config.bindHost():Config.port()).
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", Config.gatewayPort()), 0)
        server.executor = Executors.newFixedThreadPool(8)

        server.createContext("/healthz") { ex ->
            handle(ex) {
                if (ex.requestMethod != "GET") return@handle send(ex, 405, error("method_not_allowed", "Только GET"))
                send(ex, 200, buildJsonObject {
                    put("status", "ok")
                    put("model", Config.deepSeekModel())
                    put("llm_configured", client != null)
                    put("uptime_sec", (System.currentTimeMillis() - startedAt) / 1000)
                    put("requests_served", served.get())
                })
            }
        }
        server.createContext("/v1/chat") { ex -> handle(ex) { handleChat(ex) } }
        server.createContext("/v1/audit") { ex -> handle(ex) { handleAudit(ex) } }

        server.start()
        return server
    }

    // ── POST /v1/chat ────────────────────────────────────────────────────

    private fun handleChat(ex: HttpExchange) {
        val startedNs = System.nanoTime()
        if (ex.requestMethod != "POST") return send(ex, 405, error("method_not_allowed", "Только POST"))

        // Rate limit — до любой работы: дешёвый отказ дешевле дорогого вызова LLM.
        val ip = ex.remoteAddress.address.hostAddress
        val decision = limiter.check(ip)
        if (!decision.allowed) {
            ex.responseHeaders.add("Retry-After", decision.retryAfterSec.toString())
            return send(
                ex, 429,
                error(
                    "rate_limited",
                    "Слишком часто: лимит ${Config.rateLimitPerMin()} запросов/мин с одного IP. " +
                        "Повторите через ${decision.retryAfterSec} с.",
                ),
            )
        }

        // 413 по Content-Length ДО чтения тела; само чтение — с потолком.
        val cap = Config.maxBodyBytes()
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > cap) {
            return send(ex, 413, error("payload_too_large", "Тело $declared байт — больше потолка $cap"))
        }
        val bytes = ex.requestBody.readNBytes(cap + 1)
        if (bytes.size > cap) {
            return send(ex, 413, error("payload_too_large", "Тело больше потолка $cap байт"))
        }

        // Парсинг и валидация тела.
        val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return send(ex, 400, error("bad_request", "Тело должно быть JSON: {\"prompt\": \"…\", \"mode\": \"block|mask\"}"))
        val prompt = obj["prompt"]?.jsonPrimitive?.content
        if (prompt.isNullOrBlank()) return send(ex, 400, error("bad_request", "Поле prompt обязательно и непусто"))
        val mode = GuardMode.parse(obj["mode"]?.jsonPrimitive?.content)
            ?: return send(ex, 400, error("bad_request", "mode может быть только block или mask"))
        // Кастомный системный промпт (генерация vs security review). Он наш
        // собственный, но чужой клиент шлюза мог бы пронести в нём секрет мимо
        // стража — поэтому system тоже сканируется, всегда в mask-режиме.
        val rawSystem = obj["system"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: GatewayPrompt.SYSTEM
        val system = when (val v = inputGuard.inspect(rawSystem, GuardMode.MASK)) {
            is GuardVerdict.Masked -> v.maskedPrompt
            else -> rawSystem
        }

        // Дальше запрос считается обслуженным: он дошёл до стражей.
        served.incrementAndGet()

        // Входной страж — до любого обращения к LLM.
        when (val verdict = inputGuard.inspect(prompt, mode)) {
            is GuardVerdict.Blocked -> {
                val latency = elapsedMs(startedNs)
                audit.append(
                    AuditEntry(
                        clientIp = ip, mode = mode.name.lowercase(), status = "blocked",
                        inputAction = "blocked", inputFindings = verdict.findings,
                        promptLen = prompt.length, latencyMs = latency,
                    ),
                )
                send(ex, 200, buildJsonObject {
                    put("status", "blocked")
                    put("warning", "В промпте найдены секреты — запрос заблокирован, в LLM ничего не отправлено")
                    putJsonObject("input_guard") {
                        put("action", "blocked")
                        put("findings", json.encodeToJsonElement(verdict.findings))
                    }
                    put("latency_ms", latency)
                })
            }
            is GuardVerdict.Clean -> proxyToLlm(ex, ip, mode, system, prompt, verdict.prompt, "clean", emptyList(), startedNs)
            is GuardVerdict.Masked ->
                proxyToLlm(ex, ip, mode, system, prompt, verdict.maskedPrompt, "masked", verdict.findings, startedNs)
        }
    }

    /** Общий путь clean/masked: вызов LLM, выходной страж, стоимость, аудит, ответ. */
    private fun proxyToLlm(
        ex: HttpExchange,
        ip: String,
        mode: GuardMode,
        system: String,
        originalPrompt: String,
        promptToSend: String,
        inputAction: String,
        inputFindings: List<Finding>,
        startedNs: Long,
    ) {
        val llm = client
            ?: return send(ex, 503, error("no_api_key", "DEEPSEEK_API_KEY не задан — проксирование в LLM недоступно"))
        val result = runCatching { llm.chat(system, promptToSend) }.getOrElse { e ->
            audit.append(
                AuditEntry(
                    clientIp = ip, mode = mode.name.lowercase(), status = "upstream_error",
                    inputAction = inputAction, inputFindings = inputFindings,
                    promptLen = originalPrompt.length, model = Config.deepSeekModel(),
                    latencyMs = elapsedMs(startedNs),
                ),
            )
            return send(ex, 502, error("upstream_error", e.message?.take(300) ?: "сбой вызова LLM"))
        }

        val outVerdict = outputGuard.inspect(result.content)
        val cost = costs.track(result.promptTokens, result.completionTokens)
        val latency = elapsedMs(startedNs)
        val status = if (outVerdict.action == OutputAction.PASS && inputFindings.isEmpty()) "ok" else "guarded"

        audit.append(
            AuditEntry(
                clientIp = ip, mode = mode.name.lowercase(), status = status,
                inputAction = inputAction, inputFindings = inputFindings,
                outputAction = outVerdict.action.name.lowercase(),
                outputFindings = outVerdict.findings, outputWarnings = outVerdict.warnings,
                promptLen = originalPrompt.length, answerLen = outVerdict.answer.length,
                model = Config.deepSeekModel(),
                promptTokens = result.promptTokens, completionTokens = result.completionTokens,
                costUsd = cost, latencyMs = latency,
            ),
        )
        send(ex, 200, buildJsonObject {
            put("status", status)
            put("answer", outVerdict.answer)
            putJsonObject("input_guard") {
                put("action", inputAction)
                put("findings", json.encodeToJsonElement(inputFindings))
            }
            putJsonObject("output_guard") {
                put("action", outVerdict.action.name.lowercase())
                put("findings", json.encodeToJsonElement(outVerdict.findings))
                put("warnings", json.encodeToJsonElement(outVerdict.warnings))
            }
            put("model", Config.deepSeekModel())
            putJsonObject("usage") {
                put("prompt_tokens", result.promptTokens)
                put("completion_tokens", result.completionTokens)
            }
            put("cost_usd", cost)
            put("latency_ms", latency)
        })
    }

    // ── GET /v1/audit ────────────────────────────────────────────────────

    private fun handleAudit(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return send(ex, 405, error("method_not_allowed", "Только GET"))
        val limit = ex.requestURI.query
            ?.split("&")?.firstOrNull { it.startsWith("limit=") }
            ?.substringAfter("=")?.toIntOrNull() ?: 20
        send(ex, 200, buildJsonObject {
            put("entries", json.encodeToJsonElement(audit.tail(limit)))
            put("totals", json.encodeToJsonElement(costs.totals()))
        })
    }

    // ── Помощники ────────────────────────────────────────────────────────

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    private fun error(code: String, message: String) = buildJsonObject {
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    /** Общая обёртка: access-лог + любая ошибка становится JSON 500, а не тишиной. */
    private fun handle(ex: HttpExchange, block: () -> Unit) {
        val start = System.nanoTime()
        var status = 200
        try {
            block()
            status = ex.responseCode.takeIf { it > 0 } ?: 200
        } catch (e: Exception) {
            status = 500
            runCatching { send(ex, 500, error("internal", e.message ?: e.javaClass.simpleName)) }
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            println(
                "%s %s %s ← %s за %d мс".format(
                    ex.requestMethod,
                    ex.requestURI.path,
                    status,
                    ex.remoteAddress.address.hostAddress,
                    ms,
                ),
            )
            ex.close()
        }
    }

    private fun send(ex: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
