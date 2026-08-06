import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над `.env`.
 * Секреты никогда не хардкодятся — только DEEPSEEK_API_KEY из окружения.
 * Ключ нужен только командам, которые реально атакуют модель (`run`/`one`);
 * `catalog` и `guard` — детерминированные эшелоны обороны, они работают
 * офлайн и ключа не требуют (зависимости от LLM в RedTeamRun ленивые).
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

    /** Модель-жертва: один и тот же ассистент во всех режимах обороны. */
    fun attackModel(): String = envValue("ATTACK_MODEL") ?: "deepseek-v4-flash"

    /**
     * Порог блокировки на входе: сумма весов сработавших правил ≥ порога → Blocked.
     * Дефолт 2.0 — «одного сигнала мало, двух достаточно»: одиночное совпадение
     * (например, слово «сыграй») даёт Suspicious и уходит в модель очищенным,
     * а связка override+extraction режется до вызова LLM.
     */
    fun inputBlockThreshold(): Double = envValue("INPUT_BLOCK_THRESHOLD")?.toDoubleOrNull() ?: 2.0
}
