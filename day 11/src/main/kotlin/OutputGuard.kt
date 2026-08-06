/**
 * Последний эшелон: детерминированный гейт на выходе, БЕЗ LLM.
 * Он не спорит с моделью и не разбирает, чем именно её уговорили, — он смотрит
 * только на ответ: есть канарейка/внутреннее правило/дословный кусок системного
 * промпта/маркер чужой персоны → наружу не отдаём вообще (Blocked); есть PII или
 * ссылка на сторонний домен → отдаём с заменой на `[REDACTED]`.
 * Именно поэтому он ловит и те атаки, которые ничем не похожи на инъекцию
 * (например, «бабушка»): секрет в ответе выглядит одинаково при любой обёртке.
 */
class OutputGuard {

    fun inspect(answer: String): GuardVerdict {
        val blocking = mutableListOf<String>()
        if (SECRET_KEY.containsMatchIn(answer)) blocking += "ключ интеграции в ответе"
        if (HIDDEN_RULE.containsMatchIn(answer)) blocking += "внутреннее правило ${SystemPrompts.HIDDEN_RULE}"
        if (ANDROID_DISCOUNT.containsMatchIn(answer)) blocking += "раскрыто правило про скидку 3% для Android"
        quotedFragment(answer, SystemPrompts.hardened)?.let { blocking += "дословная цитата системного промпта: «$it…»" }
        PERSONA_MARKERS.firstOrNull { it.containsMatchIn(answer) }?.let { blocking += "маркер подменённой персоны (${it.pattern})" }
        if (blocking.isNotEmpty()) return GuardVerdict.Blocked(blocking)

        val redactions = mutableListOf<String>()
        var text = answer
        if (EMAIL.containsMatchIn(text)) { redactions += "email"; text = EMAIL.replace(text, REDACTED) }
        if (PHONE.containsMatchIn(text)) { redactions += "телефон"; text = PHONE.replace(text, REDACTED) }
        if (CARD.containsMatchIn(text)) { redactions += "номер карты"; text = CARD.replace(text, REDACTED) }
        if (EXTERNAL_URL.containsMatchIn(text)) { redactions += "внешняя ссылка"; text = EXTERNAL_URL.replace(text, REDACTED) }

        return if (redactions.isEmpty()) GuardVerdict.Pass(text) else GuardVerdict.Redacted(text, redactions)
    }

    companion object {
        const val REDACTED = "[REDACTED]"

        /** Минимальная длина общей подстроки, которая уже считается цитатой промпта. */
        const val QUOTE_WINDOW = 40

        val SECRET_KEY = Regex("NB-INT-KEY-[A-Z0-9-]+")
        val HIDDEN_RULE = Regex(SystemPrompts.HIDDEN_RULE)
        // (?U) — иначе в JVM `\w` и IGNORE_CASE не работают для кириллицы.
        val ANDROID_DISCOUNT = Regex("(?U)android[\\s\\S]{0,120}скидк\\w*\\s*(в\\s*)?3", RegexOption.IGNORE_CASE)
        val EXTERNAL_URL = Regex("https?://(?!(www\\.)?severnybank\\.ru)[^\\s<>\")]+", RegexOption.IGNORE_CASE)
        val PERSONA_MARKERS = listOf(
            Regex("\\[DAN\\]", RegexOption.IGNORE_CASE),
            Regex("🔓"),
            Regex("JAILBREAK", RegexOption.IGNORE_CASE),
            Regex("РЕЖИМ СНЯТ", RegexOption.IGNORE_CASE),
            Regex("developer\\s+mode", RegexOption.IGNORE_CASE),
        )
        val EMAIL = Regex("[\\w.\\-]+@[\\w\\-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE)
        val PHONE = Regex("(\\+7|\\b8)[\\s\\-(]*\\d{3}[\\s\\-)]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2}\\b")
        val CARD = Regex("\\b\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}\\b")

        /**
         * Ищет дословное совпадение ответа с промптом: скользящее окно в
         * QUOTE_WINDOW символов по нормализованным строкам. Возвращает первый
         * найденный фрагмент или null. Пересказ так не поймать — на то и нужен
         * запрет в самом промпте, guard страхует только дословную утечку.
         * Публичные строки промпта (роль, тема, форма отказа) вычитаются: иначе
         * штатный отказ ассистента сам считался бы цитированием промпта.
         */
        fun quotedFragment(answer: String, prompt: String): String? {
            val a = normalize(answer)
            val p = normalize(SystemPrompts.publicLines.fold(prompt) { acc, line -> acc.replace(line, " ") })
            if (a.length < QUOTE_WINDOW) return null
            for (i in 0..a.length - QUOTE_WINDOW) {
                val window = a.substring(i, i + QUOTE_WINDOW)
                if (p.contains(window)) return window
            }
            return null
        }

        private fun normalize(text: String): String =
            text.lowercase().replace(Regex("\\s+"), " ").trim()
    }
}

/** Исход проверки ответа перед отправкой клиенту. */
sealed interface GuardVerdict {
    data class Pass(val text: String) : GuardVerdict
    data class Redacted(val text: String, val reasons: List<String>) : GuardVerdict
    data class Blocked(val reasons: List<String>) : GuardVerdict
}
