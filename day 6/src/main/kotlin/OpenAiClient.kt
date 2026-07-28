import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Тонкий клиент OpenAI без SDK: chat/completions (baseline на gpt-4o-mini),
 * Files API (multipart/form-data собирается руками — boundary и части в байтах)
 * и Fine-tuning API (создание job и опрос статуса). Всё на java.net.http.
 */
class OpenAiClient(
    private val apiKey: String = requireNotNull(Config.openAiApiKey()) { "OPENAI_API_KEY не задан (env или .env)" },
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Обычный чат — для baseline-замера базовой модели без файнтюна. */
    fun chat(system: String, user: String, model: String = Config.openAiBaseModel(), temperature: Double = 0.0): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("temperature", temperature)
        }
        val response = send(
            HttpRequest.newBuilder()
                .uri(URI.create("${Config.OPENAI_API_BASE}/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        )
        return json.parseToJsonElement(response).jsonObject
            .getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
    }

    /**
     * POST /v1/files, purpose=fine-tune. Multipart собирается вручную:
     * две части (purpose и file), CRLF-разделители, закрывающий boundary.
     * Возвращает id загруженного файла (file-...).
     */
    fun uploadFile(path: Path, purpose: String = "fine-tune"): String {
        val boundary = "advent-day6-${System.currentTimeMillis()}"
        val crlf = "\r\n"
        val head = buildString {
            append("--").append(boundary).append(crlf)
            append("Content-Disposition: form-data; name=\"purpose\"").append(crlf).append(crlf)
            append(purpose).append(crlf)
            append("--").append(boundary).append(crlf)
            append("Content-Disposition: form-data; name=\"file\"; filename=\"${path.fileName}\"").append(crlf)
            append("Content-Type: application/jsonl").append(crlf).append(crlf)
        }
        val tail = "$crlf--$boundary--$crlf"
        val bytes = head.toByteArray(StandardCharsets.UTF_8) +
            Files.readAllBytes(path) +
            tail.toByteArray(StandardCharsets.UTF_8)
        val response = send(
            HttpRequest.newBuilder()
                .uri(URI.create("${Config.OPENAI_API_BASE}/v1/files"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
        )
        return json.parseToJsonElement(response).jsonObject.getValue("id").jsonPrimitive.content
    }

    /** POST /v1/fine_tuning/jobs — создать задачу файнтюна на загруженном файле. */
    fun createFineTuneJob(trainingFileId: String, model: String, suffix: String): JsonObject {
        val body = fineTuneJobBody(trainingFileId, model, suffix)
        val response = send(
            HttpRequest.newBuilder()
                .uri(URI.create("${Config.OPENAI_API_BASE}/v1/fine_tuning/jobs"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        )
        return json.parseToJsonElement(response).jsonObject
    }

    /** GET /v1/fine_tuning/jobs/{id} — статус задачи (для polling). */
    fun getFineTuneJob(jobId: String): JsonObject {
        val response = send(
            HttpRequest.newBuilder()
                .uri(URI.create("${Config.OPENAI_API_BASE}/v1/fine_tuning/jobs/$jobId"))
                .header("Authorization", "Bearer $apiKey")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
        )
        return json.parseToJsonElement(response).jsonObject
    }

    private fun send(request: HttpRequest): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "OpenAI ${request.uri().path} → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return response.body()
    }

    companion object {
        /** Тело запроса job — вынесено, чтобы dry-run печатал ровно то, что уйдёт в API. */
        fun fineTuneJobBody(trainingFileId: String, model: String, suffix: String): JsonObject = buildJsonObject {
            put("training_file", trainingFileId)
            put("model", model)
            put("suffix", suffix)
        }
    }
}
