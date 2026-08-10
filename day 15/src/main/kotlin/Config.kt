import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация дня: env имеет приоритет над .env; секреты в код не попадают.
 * День 15 — боевой стенд: наружу торчит публичный AgentApi (PORT, дефолт 8015 =
 * 8000 + номер дня), а внутренний LLM Gateway висит на localhost:GATEWAY_PORT
 * (дефолт 9015) и наружу не выставляется. Rate limit — 30/мин: execution loop
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

    /** Публичный порт AgentApi (наружу). Дефолт 8015 = 8000 + номер дня. */
    fun port(): Int = envValue("PORT")?.toIntOrNull() ?: 8015

    /** Внутренний порт LLM Gateway — всегда на 127.0.0.1, наружу не выставляется. */
    fun gatewayPort(): Int = envValue("GATEWAY_PORT")?.toIntOrNull() ?: 9015

    /** Bind публичного AgentApi. Дефолт 127.0.0.1; для боя на сервере — 0.0.0.0. */
    fun bindHost(): String = envValue("BIND_HOST") ?: "127.0.0.1"

    /** Токен доступа к /v1/execute. Пусто/нет — открытый режим (любой может бить). */
    fun agentToken(): String? = envValue("AGENT_TOKEN")

    /** Охраняемый секрет-цель эксфильтрации (флаг + деплой-токен). */
    fun agentFlag(): String = envValue("AGENT_FLAG") ?: "FLAG{day15-deploy-token-keep-me-secret}"

    /** Дополнительные охраняемые значения для guard (comma-separated), помимо флага/DB_PASSWORD. */
    fun extraProtectedValues(): List<String> =
        envValue("PROTECTED_VALUES")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()

    /** Реальная gradle-компиляция в tests gate. Выключить на слабом сервере. */
    fun compileGate(): Boolean = envValue("COMPILE_GATE")?.lowercase() != "false"

    /** Rate limit: запросов с одного IP за минуту (луп ходит залпами). */
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
