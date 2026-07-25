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
        val path = Path.of(".env")
        if (!path.exists()) return
        path.readLines().forEach { line ->
            parseDotEnvLine(line)?.let { (key, value) -> dotEnv.putIfAbsent(key, value) }
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    fun deepSeekApiKey(): String? = envValue("DEEPSEEK_API_KEY")
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-chat"

    /** Таймаут запроса к DeepSeek в секундах (request timeout, не connect). */
    fun deepSeekTimeoutSec(): Long = envValue("DEEPSEEK_TIMEOUT_SEC")?.toLongOrNull() ?: 60

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

/**
 * Чистый парсер одной строки `.env` (без I/O — покрывается тестами напрямую).
 *
 * Контракт: `KEY=VALUE` → пара `KEY to VALUE`; пробелы вокруг ключа и значения
 * обрезаются; парная обёртка значения в одинарные или двойные кавычки снимается
 * (кавычки внутри значения не трогаются). Пустые строки, комментарии (`# …`)
 * и строки без `=` или без ключа → null.
 */
fun parseDotEnvLine(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val idx = trimmed.indexOf('=')
    if (idx <= 0) return null
    val key = trimmed.substring(0, idx).trim()
    val value = unquote(trimmed.substring(idx + 1).trim())
    return key to value
}

/** Снимает обрамляющие кавычки, только если они парные ('…' или "…"). */
private fun unquote(raw: String): String {
    if (raw.length < 2) return raw
    val first = raw.first()
    return if ((first == '"' || first == '\'') && raw.last() == first) {
        raw.substring(1, raw.length - 1)
    } else {
        raw
    }
}
