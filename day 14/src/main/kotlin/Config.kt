import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над .env; секреты в код не попадают.
 * Порт встроенного шлюза по умолчанию 8014 (8000 + номер дня), переопределяется
 * через PORT. Rate limit поднят до 30/мин против 10 у day 13: execution loop
 * делает залпы вызовов (генерация + review на каждой итерации).
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
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-v4-flash"
    fun port(): Int = envValue("PORT")?.toIntOrNull() ?: 8014
    fun bindHost(): String = envValue("BIND_HOST") ?: "127.0.0.1"

    /** Rate limit: запросов к /v1/chat с одного IP за минуту (луп ходит залпами). */
    fun rateLimitPerMin(): Int = envValue("RATE_LIMIT_PER_MIN")?.toIntOrNull() ?: 30

    /** Максимум итераций лупа на одну задачу. */
    fun maxIterations(): Int = envValue("MAX_ITERATIONS")?.toIntOrNull() ?: 3

    /** Потолок тела запроса: проверяется по Content-Length до чтения. */
    fun maxBodyBytes(): Int = envValue("MAX_BODY_BYTES")?.toIntOrNull() ?: 64 * 1024

    // Цены DeepSeek за 1M токенов, USD — приблизительные ориентиры для
    // cost-tracking'а, не оферта; актуальные — на странице тарифов DeepSeek.
    fun priceInputPer1M(): Double = envValue("PRICE_INPUT_PER_1M")?.toDoubleOrNull() ?: 0.28
    fun priceOutputPer1M(): Double = envValue("PRICE_OUTPUT_PER_1M")?.toDoubleOrNull() ?: 0.42
}
