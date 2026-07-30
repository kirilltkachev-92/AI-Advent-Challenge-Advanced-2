import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над `.env`.
 * Секреты никогда не хардкодятся — только DEEPSEEK_API_KEY из окружения.
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

    fun deepSeekApiKey(): String =
        requireNotNull(envValue("DEEPSEEK_API_KEY")) { "DEEPSEEK_API_KEY не задан (env или .env)" }

    /** Дешёвый/быстрый ярус роутинга — первым пробует каждый запрос. */
    fun cheapModel(): String = envValue("CHEAP_MODEL") ?: "deepseek-v4-flash"

    /** Сильный ярус — принимает эскалации; третьего яруса нет. */
    fun strongModel(): String = envValue("STRONG_MODEL") ?: "deepseek-v4-pro"
}
