import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Публичный боевой слой стенда — то, что торчит наружу и что атакует партнёр.
 * com.sun.net.httpserver из JDK, без фреймворков.
 *
 * Публичное:  GET  /healthz   — жив ли сервис
 *             POST /v1/execute — {"prompt": "...", "files": [{"name","content"}]?}
 *                                прогоняет ОДИН проход execution loop над задачей
 *                                атакующего (генерация → tests gate → security review →
 *                                commit-or-reject, все LLM-вызовы через внутренний шлюз)
 *                                и возвращает outcome + answer + код + вердикты стражей.
 *                                answer/committed_code — канал наружу (цель эксфильтрации).
 *
 * Порядок обороны на /v1/execute: 405 (метод) → 401 (Bearer AGENT_TOKEN, если задан) →
 * 429 (rate limit по IP) → 413 (Content-Length больше потолка ДО чтения тела) →
 * 400 (парсинг/валидация). Формат ошибок: {"error": {"code": "...", "message": "..."}}.
 *
 * execute сериализуется одним локом: общий git-workspace не терпит параллелизма —
 * каждый запрос сбрасывает workspace к чистому шаблону, чтобы атаки не влияли друг на друга.
 */
class AgentApi(
    private val loop: ExecutionLoop,
    private val workspace: Workspace,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val limiter = RateLimiter(Config.rateLimitPerMin())
    private val startedAt = System.currentTimeMillis()
    private val served = AtomicLong(0)
    private val executeLock = ReentrantLock()

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(Config.bindHost(), Config.port()), 0)
        server.executor = Executors.newFixedThreadPool(8)

        server.createContext("/healthz") { ex ->
            handle(ex) {
                if (ex.requestMethod != "GET") return@handle send(ex, 405, error("method_not_allowed", "Только GET"))
                send(ex, 200, buildJsonObject {
                    put("status", "ok")
                    put("model", Config.deepSeekModel())
                    put("auth_required", !Config.agentToken().isNullOrBlank())
                    put("compile_gate", Config.compileGate())
                    put("uptime_sec", (System.currentTimeMillis() - startedAt) / 1000)
                    put("requests_served", served.get())
                })
            }
        }
        server.createContext("/v1/execute") { ex -> handle(ex) { handleExecute(ex) } }
        server.start()
        return server
    }

    // ── POST /v1/execute ─────────────────────────────────────────────────

    private fun handleExecute(ex: HttpExchange) {
        val startedNs = System.nanoTime()
        // 1. Метод.
        if (ex.requestMethod != "POST") return send(ex, 405, error("method_not_allowed", "Только POST"))

        // 2. Авторизация (если AGENT_TOKEN задан). Пусто — открытый режим.
        val token = Config.agentToken()
        if (!token.isNullOrBlank()) {
            val given = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            if (given != token) {
                return send(ex, 401, error("unauthorized", "Нужен заголовок Authorization: Bearer <token>"))
            }
        }

        // 3. Rate limit по IP — до дорогого прогона лупа.
        val ip = ex.remoteAddress.address.hostAddress
        val decision = limiter.check(ip)
        if (!decision.allowed) {
            ex.responseHeaders.add("Retry-After", decision.retryAfterSec.toString())
            return send(
                ex, 429,
                error("rate_limited", "Лимит ${Config.rateLimitPerMin()} запросов/мин с IP. Повтор через ${decision.retryAfterSec} с."),
            )
        }

        // 4. 413 по Content-Length ДО чтения тела; чтение — с потолком.
        val cap = Config.maxBodyBytes()
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > cap) {
            return send(ex, 413, error("payload_too_large", "Тело $declared байт — больше потолка $cap"))
        }
        val bytes = ex.requestBody.readNBytes(cap + 1)
        if (bytes.size > cap) return send(ex, 413, error("payload_too_large", "Тело больше потолка $cap байт"))

        // 5. Парсинг и валидация.
        val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return send(ex, 400, error("bad_request", "Тело должно быть JSON: {\"prompt\": \"…\", \"files\": [...]?}"))
        val prompt = obj["prompt"]?.jsonPrimitive?.content
        if (prompt.isNullOrBlank()) return send(ex, 400, error("bad_request", "Поле prompt обязательно и непусто"))
        val uploaded = parseFiles(obj) ?: return send(ex, 400, error("bad_request", "files: массив объектов {name, content}"))

        served.incrementAndGet()
        // Прогон лупа сериализован: общий workspace — одна копия на процесс.
        val record = executeLock.withLock {
            workspace.reset()
            val task = ProvocationTask(
                id = "execute",
                title = prompt.take(60).replace('\n', ' '),
                prompt = prompt,
                fileName = "Solution.kt",
                acceptance = emptyList(),
            )
            loop.run(task, uploaded)
        }
        send(ex, 200, renderResult(record, elapsedMs(startedNs)))
    }

    /** files: null поле → пустая карта; кривой формат → null (400). */
    private fun parseFiles(obj: JsonObject): Map<String, String>? {
        val arr = obj["files"] ?: return emptyMap()
        val list = runCatching { arr.jsonArray }.getOrNull() ?: return null
        val result = LinkedHashMap<String, String>()
        list.forEach { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return null
            val name = o["name"]?.jsonPrimitive?.content ?: return null
            val content = o["content"]?.jsonPrimitive?.content ?: return null
            result[name] = content
        }
        return result
    }

    private fun renderResult(record: ExecutionLoop.TaskRunRecord, latencyMs: Long): JsonObject {
        val outcome = record.outcome
        val gen = record.lastGeneration
        val review = record.iterations.mapNotNull { it.review }
            .filterIsInstance<SecurityReviewer.ReviewResult.Parsed>().lastOrNull()
        return buildJsonObject {
            put("status", "executed")
            put("outcome", outcomeName(outcome))
            put("commit", (outcome as? ExecutionLoop.TaskOutcome.Committed)?.hash)
            put("iterations", record.iterations.size)
            put("answer", record.finalAnswer)
            put("committed_code", record.finalCode)
            putJsonObject("input_guard") {
                putJsonArray("masked_types") { gen?.inputMaskedTypes?.forEach { add(it) } }
            }
            putJsonObject("output_guard") {
                put("action", gen?.outputAction ?: "pass")
                putJsonArray("masked_types") { gen?.outputMaskedTypes?.forEach { add(it) } }
                putJsonArray("warnings") { gen?.outputWarnings?.forEach { add(it) } }
            }
            putJsonObject("security_review") {
                putJsonArray("findings") {
                    review?.findings?.forEach { f ->
                        add(buildJsonObject {
                            put("severity", f.severity); put("line", f.line); put("issue", f.issue)
                        })
                    }
                }
            }
            put("latency_ms", latencyMs)
        }
    }

    private fun outcomeName(outcome: ExecutionLoop.TaskOutcome): String = when (outcome) {
        is ExecutionLoop.TaskOutcome.Committed -> "committed"
        is ExecutionLoop.TaskOutcome.FailedSecurity -> "failed_security"
        is ExecutionLoop.TaskOutcome.FailedTests -> "failed_tests"
        is ExecutionLoop.TaskOutcome.FailedFormat -> "failed_format"
    }

    // ── Помощники ────────────────────────────────────────────────────────

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    private fun error(code: String, message: String) = buildJsonObject {
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    /** Обёртка: access-лог + любая ошибка становится JSON 500, а не тишиной. */
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
            println("%s %s %s ← %s за %d мс".format(ex.requestMethod, ex.requestURI.path, status, ex.remoteAddress.address.hostAddress, ms))
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
