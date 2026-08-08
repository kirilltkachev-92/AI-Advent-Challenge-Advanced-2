import kotlinx.serialization.Serializable
import java.util.Base64

/** Тип найденного секрета; placeholder — типизированная маска для режима mask. */
enum class SecretType(val placeholder: String) {
    API_KEY("[REDACTED_API_KEY]"),
    AWS_KEY("[REDACTED_AWS_KEY]"),
    AWS_SECRET("[REDACTED_AWS_SECRET]"),
    EMAIL("[REDACTED_EMAIL]"),
    CARD("[REDACTED_CARD]"),
    PHONE("[REDACTED_PHONE]"),
}

/**
 * Одна находка сканера. `fragment` — всегда замаскированная середина
 * (например `sk-pr…c123`): полный секрет не попадает ни в ответ, ни в лог.
 * `via`: direct — прямое совпадение, base64 — секрет внутри base64-токена,
 * normalized — секрет, собранный из кусков (`"sk-" + "proj-…"`).
 */
@Serializable
data class Finding(
    val type: SecretType,
    val fragment: String,
    val position: Int,
    val length: Int,
    val via: String,
)

/**
 * Детектор секретов, общий для input- и output-guard. Три прохода:
 * 1) прямые regex-детекторы (API-ключи, AWS, email, карты c Luhn, телефоны);
 * 2) base64-токены: декодируем и прогоняем прямые детекторы по расшифровке;
 * 3) нормализация (убираем кавычки/плюсы/пробелы) — ловит секреты, разбитые
 *    конкатенацией; позиции находок отображаются обратно в исходный текст.
 * Пересекающиеся находки схлопываются: побеждает более ранний проход.
 */
class SecretScanner {

    private data class RawHit(val type: SecretType, val start: Int, val end: Int, val secret: String, val via: String)

    // Прямые детекторы. Порядок = приоритет при пересечении диапазонов.
    private val skKey = Regex("""(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{8,}""")
    private val ghpKey = Regex("""(?<![A-Za-z0-9])ghp_[A-Za-z0-9]{20,}""")
    private val awsKey = Regex("""(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![0-9A-Z])""")
    private val awsSecretCandidate = Regex("""(?<![A-Za-z0-9/+=])[A-Za-z0-9/+=]{40}(?![A-Za-z0-9/+=])""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val card = Regex("""(?<!\d)(?:\d[ \-]?){12,18}\d(?!\d)""")
    private val phone =
        Regex("""(?<![0-9A-Za-z])(?:\+\d{1,3}|8)[\s\-()]{0,3}\d{3}[\s\-()]{0,3}\d{3}[\s\-]{0,2}\d{2}[\s\-]{0,2}\d{2}(?!\d)""")
    private val base64Token = Regex("""(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_-]{16,}={0,2}(?![A-Za-z0-9+/=_-])""")

    // Символы, которые убирает нормализация: кавычки, конкатенация, пробелы.
    private val normalizationNoise = setOf('"', '\'', '`', '+', '«', '»', ' ', '\t', '\n', '\r')

    /** Полный скан текста: все три прохода, находки отсортированы по позиции. */
    fun scan(text: String): List<Finding> {
        val hits = mutableListOf<RawHit>()
        directHits(text, "direct").forEach { addIfNoOverlap(hits, it) }
        base64Hits(text).forEach { addIfNoOverlap(hits, it) }
        normalizedHits(text).forEach { addIfNoOverlap(hits, it) }
        return hits.sortedBy { it.start }
            .map { Finding(it.type, maskFragment(it.secret), it.start, it.end - it.start, it.via) }
    }

    /** Замена каждой находки на типизированный placeholder (режим mask). */
    fun maskAll(text: String, findings: List<Finding>): String {
        var result = text
        findings.sortedByDescending { it.position }.forEach { f ->
            result = result.substring(0, f.position) + f.type.placeholder + result.substring(f.position + f.length)
        }
        return result
    }

    /** Середина секрета скрыта: в логи и ответы попадает только огрызок. */
    fun maskFragment(secret: String): String = when {
        secret.length <= 6 -> secret.take(1) + "…"
        secret.length <= 12 -> secret.take(3) + "…" + secret.takeLast(2)
        else -> secret.take(5) + "…" + secret.takeLast(4)
    }

    // ── Проход 1: прямые детекторы ────────────────────────────────────────

    private fun directHits(text: String, via: String): List<RawHit> {
        val hits = mutableListOf<RawHit>()
        listOf(skKey to SecretType.API_KEY, ghpKey to SecretType.API_KEY, awsKey to SecretType.AWS_KEY)
            .forEach { (regex, type) ->
                regex.findAll(text).forEach { m -> hits += RawHit(type, m.range.first, m.range.last + 1, m.value, via) }
            }
        // 40-символьная base64-подобная строка считается AWS secret'ом только
        // рядом с контекстом aws/secret — иначе слишком много ложных срабатываний.
        awsSecretCandidate.findAll(text).forEach { m ->
            val context = text.substring((m.range.first - 60).coerceAtLeast(0), m.range.first).lowercase()
            if ("aws" in context || "secret" in context || "секрет" in context) {
                hits += RawHit(SecretType.AWS_SECRET, m.range.first, m.range.last + 1, m.value, via)
            }
        }
        email.findAll(text).forEach { m ->
            hits += RawHit(SecretType.EMAIL, m.range.first, m.range.last + 1, m.value, via)
        }
        // Карты: 13–19 цифр + обязательная проверка Luhn, чтобы отсечь случайные числа.
        card.findAll(text).forEach { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length in 13..19 && luhnValid(digits)) {
                hits += RawHit(SecretType.CARD, m.range.first, m.range.last + 1, m.value, via)
            }
        }
        phone.findAll(text).forEach { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length in 10..13) {
                hits += RawHit(SecretType.PHONE, m.range.first, m.range.last + 1, m.value, via)
            }
        }
        return hits
    }

    // ── Проход 2: base64 ─────────────────────────────────────────────────

    private fun base64Hits(text: String): List<RawHit> {
        val hits = mutableListOf<RawHit>()
        base64Token.findAll(text).forEach { m ->
            val decoded = decodeBase64(m.value) ?: return@forEach
            val inner = directHits(decoded, "base64")
            if (inner.isNotEmpty()) {
                // Маскируем/логируем весь base64-токен: тип берём у первой внутренней находки.
                hits += RawHit(inner.first().type, m.range.first, m.range.last + 1, m.value, "base64")
            }
        }
        return hits
    }

    private fun decodeBase64(token: String): String? {
        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
            ?: return null
        if (bytes.size < 8) return null
        val printable = bytes.count { it in 32..126 }
        if (printable.toDouble() / bytes.size < 0.9) return null
        return String(bytes, Charsets.UTF_8)
    }

    // ── Проход 3: нормализация разбитых секретов ─────────────────────────

    private fun normalizedHits(text: String): List<RawHit> {
        val normalized = StringBuilder()
        val indexMap = mutableListOf<Int>() // позиция символа normalized → позиция в text
        text.forEachIndexed { i, ch ->
            if (ch !in normalizationNoise) {
                normalized.append(ch)
                indexMap += i
            }
        }
        return directHits(normalized.toString(), "normalized").map { hit ->
            RawHit(hit.type, indexMap[hit.start], indexMap[hit.end - 1] + 1, hit.secret, "normalized")
        }
    }

    // ── Помощники ────────────────────────────────────────────────────────

    private fun addIfNoOverlap(hits: MutableList<RawHit>, candidate: RawHit) {
        val overlaps = hits.any { maxOf(it.start, candidate.start) < minOf(it.end, candidate.end) }
        if (!overlaps) hits += candidate
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { i, ch ->
            var d = ch - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
