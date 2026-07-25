=== FILE: src/main/kotlin/Motivator.kt ===
sealed interface MotivationResult {
    data class Done(val phrase: String) : MotivationResult
    data class Failed(val reason: Exception) : MotivationResult
}

class Motivator(private val client: ChatClient) {

    private val system = """
        Ты — краткий и энергичный мотиватор для разработчика.
        Тебе дают описание задачи. Ответь ОДНОЙ мотивационной фразой
        (1–2 предложения, до 200 символов) на языке задачи.
        Фраза должна цеплять и быть привязана к сути задачи, а не быть общей банальностью.
        Без кавычек, без преамбул, без списков, без эмодзи.
    """.trimIndent()

    fun motivate(task: String): MotivationResult =
        runCatching {
            client.chat(system, task, temperature = 1.1).trim().removeSurrounding("\"")
        }.map { MotivationResult.Done(it) }.getOrElse { MotivationResult.Failed(it) }
}

=== END ===

=== FILE: src/main/kotlin/HttpApi.kt ===
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class HttpApi(private val motivator: Motivator, private val history: HistoryStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val startedAt = System.currentTimeMillis()
    private val served = AtomicLong(0)

    fun start(port: Int = Config.port()): HttpServer {
        val server = HttpServer.create(InetSocketAddress(Config.bindHost(), port), 0)
        server.executor = Executors.newFixedThreadPool(8)

        server.createContext("/") { ex ->
            handle(ex) {
                if (ex.requestURI.path != "/") return@handle send(ex, 404, error("not_found", "Нет такого пути"))
                if (ex.requestMethod != "GET") return@handle send(ex, 405, error("method_not_allowed", "Только GET"))
                sendHtml(ex, WebUi.PAGE)
            }
        }
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
                when (ex.requestMethod) {
                    "GET" -> send(ex, 200, json.encodeToString(history.snapshot()))
                    "DELETE" -> {
                        history.clear()
                        send(ex, 200, buildJsonObject { put("cleared", true) })
                    }
                    else -> send(ex, 405, error("method_not_allowed", "Только GET и DELETE"))
                }
            }
        }

        server.start()
        return server
    }

    private fun handleMotivate(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, error("method_not_allowed", "Только POST"))

        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0
        if (declared > Config.maxBodyBytes()) return send(ex, 413, error("payload_too_large", "Тело больше ${Config.maxBodyBytes()} байт"))
        val body = ex.requestBody.readNBytes(Config.maxBodyBytes() + 1)
        if (body.size > Config.maxBodyBytes()) return send(ex, 413, error("payload_too_large", "Тело больше ${Config.maxBodyBytes()} байт"))

        val task = runCatching {
            json.parseToJsonElement(body.toString(Charsets.UTF_8))
                .jsonObject.getValue("task").jsonPrimitive.content
        }.getOrNull()?.trim()
        when {
            task == null -> return send(ex, 400, error("bad_request", "Тело должно быть JSON: {\"task\": \"…\"}"))
            task.isBlank() -> return send(ex, 400, error("bad_request", "Поле task пустое"))
            task.length > Config.maxTaskChars() -> return send(ex, 400, error("bad_request", "task длиннее ${Config.maxTaskChars()} символов"))
        }

        val result = motivator.motivate(task)
        when (result) {
            is MotivationResult.Done -> {
                history.add(task, result.phrase)
                served.incrementAndGet()
                send(ex, 200, buildJsonObject { put("task", task); put("phrase", result.phrase); put("model", Config.deepSeekModel()) })
            }
            is MotivationResult.Failed -> send(ex, 502, error("upstream_error", result.reason.message ?: "DeepSeek недоступен"))
        }
    }

    private fun error(code: String, message: String) = buildJsonObject {
        putJsonObject("error") { put("code", code); put("message", message) }
    }

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
            println("%s %s %s ← %s за %d мс".format(ex.requestMethod, ex.requestURI.path, status, ex.remoteAddress.address.hostAddress, ms))
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

    private fun sendHtml(ex: HttpExchange, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}

=== END ===

=== FILE: src/test/kotlin/MotivatorTest.kt ===
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotivatorTest {

    private class RecordingClient(private val reply: String) : ChatClient {
        var system: String? = null
        var user: String? = null
        var temperature: Double? = null

        override fun chat(system: String, user: String, temperature: Double): String {
            this.system = system
            this.user = user
            this.temperature = temperature
            return reply
        }
    }

    @Test
    fun `передаёт задачу как user-сообщение с temperature 1_1`() {
        val client = RecordingClient("Дожми этот рефакторинг!")
        Motivator(client).motivate("рефакторинг легаси-модуля")

        assertEquals("рефакторинг легаси-модуля", client.user)
        assertEquals(1.1, client.temperature)
    }

    @Test
    fun `системный промпт задаёт роль мотиватора и ограничение формата`() {
        val client = RecordingClient("ответ")
        Motivator(client).motivate("задача")

        val system = client.system.orEmpty()
        assertTrue("мотиватор" in system, "промпт должен задавать роль мотиватора")
        assertTrue("200 символов" in system, "промпт должен ограничивать длину")
    }

    @Test
    fun `ответ возвращается как есть, если он уже чистый`() {
        val client = RecordingClient("Каждый тест — шаг к надёжному релизу.")
        assertEquals(
            "Каждый тест — шаг к надёжному релизу.",
            Motivator(client).motivate("покрыть сервис тестами"),
        )
    }

    @Test
    fun `обрезает пробелы и переводы строк вокруг ответа`() {
        val client = RecordingClient("\n  Вперёд к цели!  \n")
        assertEquals("Вперёд к цели!", Motivator(client).motivate("задача"))
    }

    @Test
    fun `снимает обрамляющие кавычки после trim`() {
        val client = RecordingClient(" \"Ты справишься с этим багом!\" ")
        assertEquals("Ты справишься с этим багом!", Motivator(client).motivate("задача"))
    }

    @Test
    fun `кавычки внутри фразы не трогает`() {
        val client = RecordingClient("Скажи багу \"прощай\" сегодня")
        assertEquals("Скажи багу \"прощай\" сегодня", Motivator(client).motivate("задача"))
    }
}
=== END ===