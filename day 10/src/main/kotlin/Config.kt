import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над `.env`.
 * Секреты никогда не хардкодятся — только DEEPSEEK_API_KEY из окружения.
 * Ключ нужен только командам, задействующим уровень 2 (`run`/`one`);
 * команда `micro` и сам классификатор уровня 1 работают без сети и без ключа.
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

    /** Модель уровня 2 — большая LLM, вызывается только при fallback. */
    fun fallbackModel(): String = envValue("FALLBACK_MODEL") ?: "deepseek-v4-flash"

    /**
     * Порог уверенности micro-model (score = top1·(1+margin)/2): ниже — запрос
     * уходит в LLM-fallback. Дефолт 0.20 подобран по офлайн-прогону `micro`:
     * даёт ~2/3 кейсов уровню 1 без единой ошибки среди уверенных.
     */
    fun confidenceThreshold(): Double = envValue("CONFIDENCE_THRESHOLD")?.toDoubleOrNull() ?: 0.20
}
