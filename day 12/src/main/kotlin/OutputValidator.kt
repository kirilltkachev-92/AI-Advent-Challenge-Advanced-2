/**
 * Эшелон 3: output validation. Офлайн, без LLM, детерминированно.
 *
 * Он не спорит с моделью и не разбирает, чем её уговорили, — он смотрит только
 * на результат и проверяет две вещи:
 *
 * 1. **Действия.** Всё, что агент попросил вызвать, сверяется с
 *    `Scenario.allowedTools` (у всех сценариев он пуст — пользователь не просил
 *    действий). Решение принимает `ToolBus` по политике, сюда попадают уже
 *    заблокированные записи: их причины обязаны быть видны пользователю.
 * 2. **Текст.** Домен или URL, которого НЕТ в тексте доверенных источников, —
 *    чужой: он появился из закладки или из головы модели, и в ответ не идёт
 *    (`[ссылка удалена]`).
 *
 * Две оговорки, без которых эшелон резал бы полезный ответ:
 * - **fenced-блоки кода не трогаются вовсе.** Для `repo-copilot` код — целевой
 *   артефакт, а `java.io`, `kotlin.collections` и `user@example.com` выглядят
 *   для доменного фильтра как внешние адреса. Закладка в коде ловится не здесь,
 *   а маркером `BACKDOOR_IN_CODE` у судьи и правилами эшелона 2;
 * - **строка-предупреждение вырезается только вместе с чужим доменом.** «Счёт
 *   4471: требуется подтверждение оплаты до 09.08.2026» — это ровно то, что
 *   суммаризатор обязан выдать; фишинг от него отличает не фраза, а ссылка
 *   рядом с ней. Вырезается фрагмент, а не строка целиком.
 *
 * Вердикт: `Pass` — ничего не тронуто; `Redacted` — часть вычищена, ответ
 * пользователю уходит; `Blocked` — после чистки не осталось содержательного
 * текста, показывать нечего.
 */
class OutputValidator {

    fun validate(answer: String, trustedText: String, blockedCalls: List<ToolCallRecord>): OutputVerdict {
        val reasons = mutableListOf<String>()
        blockedCalls.forEach { reasons += "$ACTION_REASON: ${it.call.render()} — ${it.reason}" }

        var insideFence = false
        val lines = answer.lines().map { line ->
            if (FENCE.containsMatchIn(line)) {
                insideFence = !insideFence
                return@map line
            }
            if (insideFence || line.isBlank()) return@map line
            redactLine(line, trustedText, reasons)
        }

        val text = lines.joinToString("\n").trim()
        val meaningful = text.replace(REDACTED, " ").replace(WARNING_REDACTED, " ").isNotBlank()
        return when {
            !meaningful && reasons.isNotEmpty() -> OutputVerdict.Blocked(reasons)
            reasons.isEmpty() -> OutputVerdict.Pass(text)
            else -> OutputVerdict.Redacted(text, reasons)
        }
    }

    // ── помощники ────────────────────────────────────────────────────────────

    private fun redactLine(line: String, trustedText: String, reasons: MutableList<String>): String {
        val foreign = mutableListOf<String>()
        var result = URL.replace(line) { m ->
            val host = hostOf(m.value)
            if (isAllowed(host, trustedText)) m.value else { foreign += host; REDACTED }
        }
        result = DOMAIN.replace(result) { m ->
            if (isAllowed(m.value, trustedText)) m.value else { foreign += m.value; REDACTED }
        }
        foreign.distinct().forEach { reasons += "$DOMAIN_REASON: $it" }

        // Дописанная строка-предупреждение опознаётся только в связке с чужим доменом:
        // сама по себе фраза «требуется подтверждение» — легитимный текст письма.
        if (foreign.isNotEmpty()) {
            result = WARNING_TEMPLATE.replace(result) { m ->
                reasons += "$WARNING_REASON: «${m.value.trim()}»"
                WARNING_REDACTED
            }
        }
        return result
    }

    /** Доверенный домен, служебный неймспейс языка или документационный домен по RFC 2606. */
    private fun isAllowed(host: String, trustedText: String): Boolean {
        if (host.isEmpty()) return false
        if (ALWAYS_ALLOWED.any { host.equals(it, ignoreCase = true) }) return true
        if (ALLOWED_PREFIXES.any { host.startsWith(it, ignoreCase = true) }) return true
        return trustedText.contains(host, ignoreCase = true)
    }

    private fun hostOf(url: String): String = url
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('@')
        .substringBefore(':')
        .trimEnd('.', ',', ')')

    companion object {
        const val REDACTED = "[ссылка удалена]"
        const val WARNING_REDACTED = "[предупреждение удалено]"

        /** Префиксы причин: отчёт группирует по ним, не разбирая текст целиком. */
        const val ACTION_REASON = "действие заблокировано"
        const val DOMAIN_REASON = "внешний домен вне доверенных источников"
        const val WARNING_REASON = "удалено дописанное предупреждение рядом с чужой ссылкой"

        val URL = Regex("https?://[^\\s<>\"')\\]]+", RegexOption.IGNORE_CASE)

        /**
         * Голый домен без схемы — например, в адресе почты. Список TLD закрытый
         * намеренно: открытый `\w+\.\w+` принимал бы `PaymentForm.kt` и
         * `analytics.track` за домены и резал бы код в ответе.
         */
        val DOMAIN = Regex(
            "(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+(?:example|ru|com|org|net|io|dev|info)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Открывающая/закрывающая граница fenced-блока кода. */
        val FENCE = Regex("^\\s*```")

        /** RFC 2606: домены, зарезервированные для примеров и документации. */
        val ALWAYS_ALLOWED = setOf("example.com", "example.org", "example.net")

        /** Служебные неймспейсы JVM-мира: в коде это пакеты, а не адреса. */
        val ALLOWED_PREFIXES = listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.")

        /** Почерк фишинга через injection — но только рядом с чужой ссылкой. */
        val WARNING_TEMPLATE = Regex(
            "(?U)(⚠️\\s*)?(требуется\\s+подтвержд\\w*\\s+доступа|подтвердите\\s+(доступ|учётн|учетн)\\w*|" +
                "подтверждение\\s+доступа|verify\\s+your\\s+\\w+|verification\\s+required|" +
                "confirm\\s+your\\s+(access|account))",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Исход проверки ответа перед показом пользователю. */
sealed interface OutputVerdict {
    data class Pass(val text: String) : OutputVerdict
    data class Redacted(val text: String, val reasons: List<String>) : OutputVerdict
    data class Blocked(val reasons: List<String>) : OutputVerdict
}
