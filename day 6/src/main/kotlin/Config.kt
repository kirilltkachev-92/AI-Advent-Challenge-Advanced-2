import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над `.env`.
 * Секреты никогда не хардкодятся — только DEEPSEEK_API_KEY / OPENAI_API_KEY из окружения.
 */
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"
    const val OPENAI_API_BASE = "https://api.openai.com"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(Path.of(".env")).forEach { path ->
            if (path.exists()) {
                path.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                    val idx = trimmed.indexOf('=')
                    if (idx <= 0) return@forEach
                    dotEnv.putIfAbsent(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim())
                }
            }
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    fun deepSeekApiKey(): String? = envValue("DEEPSEEK_API_KEY")
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-v4-flash"

    fun openAiApiKey(): String? = envValue("OPENAI_API_KEY")
    fun openAiBaseModel(): String = envValue("OPENAI_BASE_MODEL") ?: "gpt-4o-mini"
    fun fineTuneModel(): String = envValue("FINETUNE_MODEL") ?: "gpt-4o-mini-2024-07-18"

    fun ollamaBaseUrl(): String = envValue("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    fun localModel(): String = envValue("LOCAL_MODEL") ?: "qwen2.5:14b"
}
