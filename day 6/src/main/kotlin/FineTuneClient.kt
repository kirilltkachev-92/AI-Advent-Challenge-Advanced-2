import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Оркестрация файнтюна OpenAI: upload(train.jsonl) → создать fine-tuning job →
 * poll статуса до succeeded/failed. По условию дня реальный запуск НЕ выполняется:
 * по умолчанию dry-run — печатает пошагово, какие запросы с какими телами уйдут
 * в API. Реальный запуск только с флагом --go и при наличии OPENAI_API_KEY.
 */
class FineTuneClient(
    private val trainPath: Path = Path.of("data/train.jsonl"),
    private val pollIntervalSeconds: Long = 15,
    // потолок ожидания: файнтюн, висящий дольше, разумнее проверять руками
    private val maxPollAttempts: Int = 480,
) {
    fun run(go: Boolean) {
        check(Files.exists(trainPath)) { "$trainPath не найден — сначала ./run.sh prepare" }
        val lines = Files.readAllLines(trainPath).count { it.isNotBlank() }
        val model = Config.fineTuneModel()
        val suffix = "advent-day6"

        println("План файнтюна ($trainPath, $lines примеров, модель $model):")
        println()
        println("1) POST ${Config.OPENAI_API_BASE}/v1/files")
        println("   Content-Type: multipart/form-data; boundary=<generated>")
        println("   поля: purpose=fine-tune, file=${trainPath.fileName} (${Files.size(trainPath)} байт)")
        println("   ← {\"id\": \"file-...\"}")
        println()
        println("2) POST ${Config.OPENAI_API_BASE}/v1/fine_tuning/jobs")
        println("   ${OpenAiClient.fineTuneJobBody("file-<из шага 1>", model, suffix)}")
        println("   ← {\"id\": \"ftjob-...\", \"status\": \"validating_files\"}")
        println()
        println("3) GET ${Config.OPENAI_API_BASE}/v1/fine_tuning/jobs/{ftjob-id}")
        println("   каждые $pollIntervalSeconds с до status ∈ {succeeded, failed, cancelled}")
        println("   при succeeded в ответе появится fine_tuned_model — её и подставлять в baseline")
        println()

        if (!go) {
            println("Dry-run: запросы НЕ отправлены (по условию дня файнтюн не запускаем).")
            println("Реальный запуск: OPENAI_API_KEY в .env + ./run.sh finetune --go")
            return
        }
        val apiKey = Config.openAiApiKey()
        if (apiKey == null) {
            println("--go указан, но OPENAI_API_KEY не задан — запуск невозможен.")
            return
        }
        execute(OpenAiClient(apiKey), model, suffix)
    }

    /** Реальный сценарий: работает только с --go, шаги те же, что напечатаны в плане. */
    private fun execute(api: OpenAiClient, model: String, suffix: String) {
        println("Запуск по-настоящему…")
        val fileId = api.uploadFile(trainPath)
        println("файл загружен: $fileId")
        val job = api.createFineTuneJob(fileId, model, suffix)
        val jobId = job.getValue("id").jsonPrimitive.content
        println("job создан: $jobId, статус ${job["status"]?.jsonPrimitive?.content}")
        repeat(maxPollAttempts) {
            Thread.sleep(pollIntervalSeconds * 1000)
            val current = api.getFineTuneJob(jobId)
            val status = current["status"]?.jsonPrimitive?.content ?: "unknown"
            println("статус: $status")
            if (status in setOf("succeeded", "failed", "cancelled")) {
                current["fine_tuned_model"]?.jsonPrimitive?.content?.let { println("готовая модель: $it") }
                current["error"]?.let { println("ошибка: $it") }
                return
            }
        }
        println("Потолок ожидания (${maxPollAttempts * pollIntervalSeconds / 60} мин) исчерпан — " +
            "проверь статус вручную: GET /v1/fine_tuning/jobs/$jobId")
    }
}
