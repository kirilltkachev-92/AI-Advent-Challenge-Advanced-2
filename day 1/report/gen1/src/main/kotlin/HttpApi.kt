import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP-слой сервиса — com.sun.net.httpserver из JDK, без фреймворков.
 *
 * GET  /healthz     — жив ли сервис (модель, аптайм, счётчик запросов)
 * POST /v1/motivate — {"task": "..."} → {"task", "phrase", "model"}
 * GET  /v1/history  — последние 10 пар запрос/ответ, свежие первыми
 *
 * Оборона по порядку: 405 (метод) → 413 (task слишком длинный) →
 * 400 (кривое тело) → 502 (DeepSeek недоступен). Ошибки — JSON {error:{code,message}}.
 */
class HttpApi(
    private val motivator: Motivator,
    private val history: HistoryStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val startedAt = System.currentTimeMillis()
    private val served = AtomicLong(0)

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(Config.bindHost(), Config.port()), 0)
        server.executor = Executors.newFixedThreadPool(8)

        server.createContext("/healthz") { ex ->
            handle(ex) {
                if (ex.requestMethod != "GET") return@handle send(ex, 405, error("method_not_allowed", "Только GET"))
                send(ex, 200, buildJsonObject {
                    put("status", "ok")
                    put("model", Config.deepSeekModel())
                    put("uptime_sec", (System.currentTimeMillis() - startedAt) / 1000)
                    put("requests_served", served.get())
                })
            }
        }
        server.createContext("/v1/motivate") { ex ->
            handle(ex) { handleMotivate(ex) }
        }
        server.createContext("/v1/history") { ex ->
            handle(ex) {
                if (ex.requestMethod != "GET") return@handle send(ex, 405, error("method_not_allowed", "Только GET"))
                send(ex, 200, buildJsonObject {
                    putJsonArray("history") {
                        history.latest().forEach { entry ->
                            add(buildJsonObject {
                                put("task", entry.task)
                                put("phrase", entry.phrase)
                                put("at", entry.at)
                            })
                        }
                    }
                })
            }
        }

        server.start()
        return server
    }

    private fun handleMotivate(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, error("method_not_allowed", "Только POST"))

        val task = parseTask(ex) ?: return
        if (task.length > Config.maxTaskChars()) {
            return send(
                ex, 413,
                error("task_too_long", "Задача ${task.length} символов — больше лимита ${Config.maxTaskChars()}"),
            )
        }

        when (val result = motivator.motivate(task)) {
            is MotivationResult.Done -> {
                history.add(task, result.phrase)
                served.incrementAndGet()
                send(ex, 200, buildJsonObject {
                    put("task", task)
                    put("phrase", result.phrase)
                    put("model", Config.deepSeekModel())
                })
            }
            is MotivationResult.Failed ->
                send(ex, 502, error("llm_unavailable", "DeepSeek не ответил: ${result.reason}"))
        }
    }

    /** null — ответ 400 уже отправлен. */
    private fun parseTask(ex: HttpExchange): String? {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val task = runCatching {
            json.parseToJsonElement(body).jsonObject.getValue("task").jsonPrimitive.content.trim()
        }.getOrNull()
        if (task.isNullOrBlank()) {
            send(ex, 400, error("bad_request", "Тело должно быть JSON: {\"task\": \"непустая строка\"}"))
            return null
        }
        return task
    }

    private fun error(code: String, message: String) = buildJsonObject {
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    /** Общая обёртка: access-лог + любая ошибка становится JSON 500, а не тишиной. */
    private fun handle(ex: HttpExchange, block: () -> Unit) {
        val start = System.nanoTime()
        try {
            block()
        } catch (e: Exception) {
            runCatching { send(ex, 500, error("internal", e.message ?: e.javaClass.simpleName)) }
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            println(
                "%s %s %s ← %s за %d мс".format(
                    java.time.LocalTime.now().withNano(0),
                    ex.requestMethod,
                    ex.requestURI.path,
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
