import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация сервиса: env имеет приоритет над `.env` в рабочей директории.
 * Секреты (ключ DeepSeek) — только отсюда, в код и git не попадают.
 */
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

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
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-chat"

    fun bindHost(): String = envValue("BIND_HOST") ?: "127.0.0.1"

    // По условию дня сервис живёт на 8080 (осознанное отступление от конвенции 8000+N).
    fun port(): Int = envValue("PORT")?.toIntOrNull() ?: 8080

    /** Потолок длины поля task — защита от чрезмерных промптов. */
    fun maxTaskChars(): Int = envValue("MAX_TASK_CHARS")?.toIntOrNull() ?: 2000

    /** Потолок размера тела запроса в байтах (проверяется до чтения). */
    fun maxBodyBytes(): Int = envValue("MAX_BODY_BYTES")?.toIntOrNull() ?: 16_384

    /** Сколько последних записей держит история в памяти. */
    fun historySize(): Int = envValue("HISTORY_SIZE")?.toIntOrNull() ?: 10
}
