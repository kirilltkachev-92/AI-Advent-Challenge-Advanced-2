import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над `.env`.
 * Секреты никогда не хардкодятся — только DEEPSEEK_API_KEY из окружения.
 * Модель конфигурируется на КАЖДЫЙ этап конвейера отдельно (идея дня 8:
 * дешёвая модель там, где задача простая) — по умолчанию все на flash.
 */
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private const val DEFAULT_MODEL = "deepseek-v4-flash"

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

    /** Этап 1 «Нормализация»: зашумлённый текст → компактные поля. */
    fun stage1Model(): String = envValue("STAGE1_MODEL") ?: DEFAULT_MODEL

    /** Этап 2 «Решение»: компактные поля → enum-решение по правилам. */
    fun stage2Model(): String = envValue("STAGE2_MODEL") ?: DEFAULT_MODEL

    /** Этап 3 «Формирование результата»: поля + решение → финальный JSON с сообщением. */
    fun stage3Model(): String = envValue("STAGE3_MODEL") ?: DEFAULT_MODEL

    /** Монолитный вариант A: всё одним промптом и одним вызовом. */
    fun monoModel(): String = envValue("MONO_MODEL") ?: DEFAULT_MODEL
}
