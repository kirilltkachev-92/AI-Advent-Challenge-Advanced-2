import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционный тест HTTP-слоя: реальный HttpServer на случайном свободном
 * порту (порт 0), DeepSeek подменён фейковым ChatClient — сеть не трогаем.
 * Проверяются ветви обороны /v1/motivate (405 → 413 → 400 → 502 → 200)
 * и вспомогательные endpoint'ы /healthz и /v1/history.
 */
class HttpApiTest {

    /** Фейковый LLM: на задачу "boom" падает (ветвь 502), иначе отвечает детерминированно. */
    private val fakeClient = ChatClient { _, user, _ ->
        if (user == "boom") error("DeepSeek недоступен (фейк)") else "Мотивация: $user"
    }

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun startServer() {
        server = HttpApi(Motivator(fakeClient), HistoryStore(capacity = 10)).start(port = 0)
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    // ── помощники ──

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun delete(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun motivate(task: String): HttpResponse<String> =
        post("/v1/motivate", buildJsonObject { put("task", task) }.toString())

    private fun errorCode(body: String): String =
        json.parseToJsonElement(body).jsonObject
            .getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    // ── GET / — веб-UI и 404 на неизвестные пути ──

    @Test
    fun `GET корня отдаёт 200 и html-страницу веб-UI`() {
        val response = get("/")
        assertEquals(200, response.statusCode())
        assertTrue(
            response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
            "Content-Type страницы — text/html",
        )
        val page = response.body()
        assertTrue(page.contains("<!doctype html"), "страница — самодостаточный HTML")
        // Стабильные id — контракт для автотестов UI
        listOf("taskInput", "motivateBtn", "phraseBox", "errorBox", "historyList", "clearHistoryBtn").forEach { id ->
            assertTrue(page.contains("id=\"$id\""), "на странице есть элемент #$id")
        }
    }

    @Test
    fun `POST на корень отклоняется 405`() {
        val response = post("/", "{}")
        assertEquals(405, response.statusCode())
        assertEquals("method_not_allowed", errorCode(response.body()))
    }

    @Test
    fun `GET неизвестного пути отдаёт 404 JSON`() {
        val response = get("/nope")
        assertEquals(404, response.statusCode())
        assertEquals("not_found", errorCode(response.body()))
    }

    // ── /v1/motivate: оборона по порядку ──

    @Test
    fun `motivate GET отклоняется 405`() {
        val response = get("/v1/motivate")
        assertEquals(405, response.statusCode())
        assertEquals("method_not_allowed", errorCode(response.body()))
    }

    @Test
    fun `motivate с телом больше потолка отклоняется 413`() {
        val huge = buildJsonObject { put("task", "x".repeat(Config.maxBodyBytes() + 100)) }
        val response = post("/v1/motivate", huge.toString())
        assertEquals(413, response.statusCode())
        assertEquals("payload_too_large", errorCode(response.body()))
    }

    @Test
    fun `motivate с не-JSON телом отклоняется 400`() {
        val response = post("/v1/motivate", "это не json")
        assertEquals(400, response.statusCode())
        assertEquals("bad_request", errorCode(response.body()))
    }

    @Test
    fun `motivate без поля task отклоняется 400`() {
        val response = post("/v1/motivate", """{"text": "задача"}""")
        assertEquals(400, response.statusCode())
        assertEquals("bad_request", errorCode(response.body()))
    }

    @Test
    fun `motivate с пустым task отклоняется 400`() {
        val response = motivate("   ")
        assertEquals(400, response.statusCode())
        assertEquals("bad_request", errorCode(response.body()))
    }

    @Test
    fun `motivate со слишком длинным task отклоняется 400`() {
        val response = motivate("a".repeat(Config.maxTaskChars() + 1))
        assertEquals(400, response.statusCode())
        assertEquals("bad_request", errorCode(response.body()))
    }

    @Test
    fun `падение LLM превращается в 502 upstream_error`() {
        val response = motivate("boom")
        assertEquals(502, response.statusCode())
        assertEquals("upstream_error", errorCode(response.body()))
    }

    @Test
    fun `успешный запрос отдаёт 200 с task, phrase и model`() {
        val response = motivate("написать интеграционный тест")
        assertEquals(200, response.statusCode())

        val body = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("написать интеграционный тест", body.getValue("task").jsonPrimitive.content)
        assertEquals("Мотивация: написать интеграционный тест", body.getValue("phrase").jsonPrimitive.content)
        assertEquals(Config.deepSeekModel(), body.getValue("model").jsonPrimitive.content)
    }

    // ── /healthz и /v1/history ──

    @Test
    fun `healthz отдаёт 200 со статусом и счётчиком обслуженных запросов`() {
        motivate("задача")

        val response = get("/healthz")
        assertEquals(200, response.statusCode())
        val body = json.parseToJsonElement(response.body()).jsonObject
        assertEquals("ok", body.getValue("status").jsonPrimitive.content)
        assertEquals(1, body.getValue("requests_served").jsonPrimitive.int)
    }

    @Test
    fun `healthz POST отклоняется 405`() {
        assertEquals(405, post("/healthz", "{}").statusCode())
    }

    @Test
    fun `history отдаёт успешные обмены свежими первыми, ошибки не попадают`() {
        motivate("первая задача")
        motivate("boom") // 502 — в историю попасть не должна
        motivate("вторая задача")

        val response = get("/v1/history")
        assertEquals(200, response.statusCode())

        val body = json.parseToJsonElement(response.body()).jsonObject
        assertEquals(2, body.getValue("count").jsonPrimitive.int)
        val entries = body.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("вторая задача", "первая задача"),
            entries.map { it.getValue("task").jsonPrimitive.content },
        )
        assertTrue(
            entries.all { it.getValue("phrase").jsonPrimitive.content.startsWith("Мотивация: ") },
            "phrase в истории — ответ мотиватора",
        )
    }

    @Test
    fun `history POST отклоняется 405`() {
        val response = post("/v1/history", "{}")
        assertEquals(405, response.statusCode())
        assertEquals("method_not_allowed", errorCode(response.body()))
    }

    @Test
    fun `history DELETE очищает историю и отдаёт cleared true`() {
        motivate("первая задача")
        motivate("вторая задача")

        val response = delete("/v1/history")
        assertEquals(200, response.statusCode())
        val body = json.parseToJsonElement(response.body()).jsonObject
        assertTrue(body.getValue("cleared").jsonPrimitive.boolean, "ответ — {\"cleared\": true}")

        val after = json.parseToJsonElement(get("/v1/history").body()).jsonObject
        assertEquals(0, after.getValue("count").jsonPrimitive.int)
        assertTrue(after.getValue("entries").jsonArray.isEmpty(), "после очистки история пуста")
    }

    @Test
    fun `history DELETE пустой истории тоже отдаёт 200`() {
        val response = delete("/v1/history")
        assertEquals(200, response.statusCode())
        assertTrue(
            json.parseToJsonElement(response.body()).jsonObject.getValue("cleared").jsonPrimitive.boolean,
        )
    }
}
