import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration

/**
 * Тонкий клиент DeepSeek (/chat/completions), протокол руками на java.net.http.
 * Единственный сетевой вызов дня: инструменты агента фейковые и в сеть не ходят.
 * Function-calling API намеренно не используется — контракт действий агент
 * возвращает обычным JSON'ом (jsonMode), чтобы стенд мерил именно решение
 * модели «вызвать инструмент», а не поведение чужого раннера.
 * Возвращает текст + usage-токены + латентность; не-200 — всегда ошибка с
 * кодом и куском тела, молча парсить чужой сбой нельзя.
 */
class DeepSeekClient(
    private val apiKey: String = Config.deepSeekApiKey(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun chat(model: String, system: String, user: String, jsonMode: Boolean = false, temperature: Double = 0.0): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("temperature", temperature)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val started = System.nanoTime()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        check(response.statusCode() == 200) {
            "DeepSeek ($model) → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        val root = json.parseToJsonElement(response.body()).jsonObject
        val content = root.getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
        val usage = root["usage"]?.jsonObject
        return ChatResult(
            content = content,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
            latencyMs = latencyMs,
        )
    }
}

/** Ответ одного вызова LLM: текст + токены из usage + латентность запроса. */
data class ChatResult(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
)
