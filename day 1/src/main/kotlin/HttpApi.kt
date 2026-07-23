import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP-слой сервиса — com.sun.net.httpserver из JDK, без фреймворков.
 *
 * GET  /healthz     — статус сервиса (модель, аптайм, счётчик запросов)
 * POST /v1/motivate — {"task": "…"} → {"task", "phrase", "model"}
 * GET  /v1/history  — последние 10 запросов и ответов (в памяти, свежие первыми)
 *
 * Порядок обороны на /v1/motivate: 405 (метод) → 413 (Content-Length больше
 * потолка — ДО чтения тела) → 400 (парсинг/валидация) → 502 (ошибка DeepSeek).
 * Формат ошибок: {"error": {"code": "…", "message": "…"}}.
 */
class HttpApi(private val motivator: Motivator, private val history: HistoryStore) {

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
                val entries = history.snapshot()
                sendRaw(ex, 200, buildString {
                    append("{\"count\":").append(entries.size)
                    append(",\"entries\":").append(json.encodeToString(entries)).append("}")
                })
            }
        }

        server.start()
        return server
    }

    private fun handleMotivate(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, error("method_not_allowed", "Только POST"))

        // Размер тела — по Content-Length до чтения; читаем с тем же потолком.
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0
        if (declared > Config.maxBodyBytes()) {
            return send(ex, 413, error("payload_too_large", "Тело больше ${Config.maxBodyBytes()} байт"))
        }
        val body = ex.requestBody.readNBytes(Config.maxBodyBytes() + 1)
        if (body.size > Config.maxBodyBytes()) {
            return send(ex, 413, error("payload_too_large", "Тело больше ${Config.maxBodyBytes()} байт"))
        }

        val task = runCatching {
            json.parseToJsonElement(body.toString(Charsets.UTF_8))
                .jsonObject.getValue("task").jsonPrimitive.content
        }.getOrNull()?.trim()
        when {
            task == null ->
                return send(ex, 400, error("bad_request", "Тело должно быть JSON: {\"task\": \"…\"}"))
            task.isBlank() ->
                return send(ex, 400, error("bad_request", "Поле task пустое"))
            task.length > Config.maxTaskChars() ->
                return send(ex, 400, error("bad_request", "task длиннее ${Config.maxTaskChars()} символов"))
        }

        val phrase = try {
            motivator.motivate(task!!)
        } catch (e: Exception) {
            return send(ex, 502, error("upstream_error", e.message ?: "DeepSeek недоступен"))
        }
        history.add(task, phrase)
        served.incrementAndGet()
        send(ex, 200, buildJsonObject {
            put("task", task)
            put("phrase", phrase)
            put("model", Config.deepSeekModel())
        })
    }

    private fun error(code: String, message: String) = buildJsonObject {
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    /** Общая обёртка: access-лог одной строкой + любая ошибка становится JSON 500. */
    private fun handle(ex: HttpExchange, block: () -> Unit) {
        val start = System.nanoTime()
        var status = -1
        try {
            block()
            status = ex.responseCode
        } catch (e: Exception) {
            runCatching { send(ex, 500, error("internal", e.message ?: e.javaClass.simpleName)) }
            status = 500
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            println(
                "%s %s %s ← %s за %d мс".format(
                    ex.requestMethod, ex.requestURI.path, status, ex.remoteAddress.address.hostAddress, ms,
                ),
            )
            ex.close()
        }
    }

    private fun send(ex: HttpExchange, status: Int, body: JsonObject) = sendRaw(ex, status, body.toString())

    private fun sendRaw(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
