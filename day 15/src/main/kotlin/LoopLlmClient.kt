import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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
 * LLM-клиент execution loop'а. Принципиально НЕ ходит в DeepSeek напрямую:
 * каждый вызов — реальный HTTP POST на встроенный шлюз (127.0.0.1:PORT/v1/chat)
 * в режиме mask, чтобы маскирование, аудит, rate limit и cost tracking честно
 * применялись к каждому вызову лупа. На 429 уважает Retry-After и повторяет.
 */
class LoopLlmClient(
    private val base: String = "http://127.0.0.1:${Config.gatewayPort()}",
    private val maxAttempts: Int = 6,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Один вызов через шлюз: system — кастомный промпт (генерация или review). */
    fun chat(system: String, prompt: String): GatewayReply {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("mode", "mask")
            put("system", system)
        }.toString()
        repeat(maxAttempts) { attempt ->
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/v1/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 429) {
                val waitSec = response.headers().firstValue("Retry-After").orElse("5").toLongOrNull() ?: 5L
                println("    шлюз → 429, ждём Retry-After=${waitSec}с (попытка ${attempt + 1}/$maxAttempts)")
                Thread.sleep(waitSec * 1000)
                return@repeat
            }
            check(response.statusCode() == 200) {
                "шлюз → HTTP ${response.statusCode()}: ${response.body().take(300)}"
            }
            return parseReply(response.body())
        }
        error("шлюз: rate limit не отпустил за $maxAttempts попыток")
    }

    private fun parseReply(raw: String): GatewayReply {
        val obj = json.parseToJsonElement(raw).jsonObject
        val status = obj.getValue("status").jsonPrimitive.content
        check(status != "blocked") { "шлюз заблокировал запрос — в режиме mask такого быть не должно" }
        val input = obj["input_guard"]?.jsonObject
        val output = obj["output_guard"]?.jsonObject
        return GatewayReply(
            answer = obj.getValue("answer").jsonPrimitive.content,
            status = status,
            inputMaskedTypes = input?.get("findings")?.jsonArray
                ?.map { it.jsonObject.getValue("type").jsonPrimitive.content } ?: emptyList(),
            outputAction = output?.get("action")?.jsonPrimitive?.content ?: "pass",
            outputMaskedTypes = output?.get("findings")?.jsonArray
                ?.map { it.jsonObject.getValue("type").jsonPrimitive.content } ?: emptyList(),
            outputWarnings = output?.get("warnings")?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList(),
            costUsd = obj["cost_usd"]?.jsonPrimitive?.double ?: 0.0,
            promptTokens = obj["usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0,
            completionTokens = obj["usage"]?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
        )
    }
}

/**
 * Ответ шлюза одному вызову лупа: текст + что поймали стражи + стоимость.
 * *_MaskedTypes — типы находок (API_KEY, EMAIL, …), сами секреты сюда не попадают.
 */
data class GatewayReply(
    val answer: String,
    val status: String,
    val inputMaskedTypes: List<String>,
    val outputAction: String,
    val outputMaskedTypes: List<String>,
    val outputWarnings: List<String>,
    val costUsd: Double,
    val promptTokens: Int,
    val completionTokens: Int,
) {
    /** Короткая сводка для консольной наррации: "masked 3 (API_KEY, EMAIL×2)". */
    fun inputSummary(): String =
        if (inputMaskedTypes.isEmpty()) "clean"
        else "masked ${inputMaskedTypes.size} (${typeCounts(inputMaskedTypes)})"

    fun outputSummary(): String = buildString {
        append(outputAction)
        if (outputMaskedTypes.isNotEmpty()) append(" ${outputMaskedTypes.size} (${typeCounts(outputMaskedTypes)})")
        if (outputWarnings.isNotEmpty()) append(", предупреждений: ${outputWarnings.size}")
    }

    private fun typeCounts(types: List<String>): String =
        types.groupingBy { it }.eachCount().entries
            .joinToString(", ") { (type, n) -> if (n == 1) type else "$type×$n" }
}
