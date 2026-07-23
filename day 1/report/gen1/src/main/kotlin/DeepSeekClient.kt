import kotlinx.serialization.json.Json
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
 * Клиент DeepSeek (OpenAI-совместимый /chat/completions), протокол вручную:
 *   {"model": "...", "messages": [{"role": "...", "content": "..."}], ...}
 *   → choices[0].message.content
 * temperature=1.0 — мотивационные фразы должны быть живыми, не под копирку.
 */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String = Config.deepSeekModel(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun chat(system: String, user: String, temperature: Double = 1.0): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("temperature", temperature)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "DeepSeek → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return json.parseToJsonElement(response.body()).jsonObject
            .getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
    }
}
