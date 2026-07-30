import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Тонкий клиент локальной модели через ollama (OpenAI-совместимый
 * /v1/chat/completions, без ключа). По UPD дня 6 baseline снимается на локальной
 * модели — этот клиент и есть та самая «локальная» точка отсчёта.
 * Таймаут запроса больше облачного: локальный инференс на CPU/GPU медленнее API.
 */
class OllamaClient(
    private val baseUrl: String = Config.ollamaBaseUrl(),
    private val model: String = Config.localModel(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Быстрая проверка «жив ли ollama» — GET /api/tags с коротким таймаутом. */
    fun isReachable(): Boolean = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/tags"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    } catch (e: Exception) {
        false
    }

    fun chat(system: String, user: String, temperature: Double = 0.0): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("temperature", temperature)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(300))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "ollama → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return json.parseToJsonElement(response.body()).jsonObject
            .getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
    }
}
