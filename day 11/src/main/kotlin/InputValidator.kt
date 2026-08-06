import java.text.Normalizer

/**
 * Первый эшелон обороны: детерминированный офлайн-гейт на входе, БЕЗ LLM.
 * Контракт: сначала нормализация (NFKC, снятие zero-width, разворачивание
 * HTML-комментариев и «белого по белому»), потом взвешенные правила по
 * нормализованному тексту ВМЕСТЕ со спрятанным содержимым — судим по тому, что
 * скрыто, а в модель отдаём то, что очищено (`sanitized`).
 * Сумма весов ≥ порога → Blocked (вызов LLM не делается вовсе); хоть одно
 * срабатывание ниже порога → Suspicious (идёт в модель, но уже без закладок);
 * иначе Clean. Гейт намеренно тупой и быстрый: он не понимает смысла, он ловит
 * форму, поэтому его нельзя уговорить — в отличие от системного промпта.
 */
class InputValidator(private val threshold: Double = Config.inputBlockThreshold()) {

    fun check(text: String): InputVerdict {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val hadZeroWidth = ZERO_WIDTH.containsMatchIn(normalized)
        var body = ZERO_WIDTH.replace(normalized, "")

        // Спрятанное вырезаем из sanitized, но сохраняем для анализа: закладка живёт именно там.
        val hidden = mutableListOf<String>()
        body = HTML_COMMENT.replace(body) { m -> hidden += m.groupValues[1]; " " }
        body = HIDDEN_SPAN.replace(body) { m -> hidden += m.groupValues[1]; " " }

        val sanitized = body
            .replace(HORIZONTAL_SPACE, " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")
        val hiddenFound = hadZeroWidth || hidden.isNotEmpty()
        val scanned = (sanitized + "\n" + hidden.joinToString("\n")).trim()

        val matched = RULES.filter { it.pattern.containsMatchIn(scanned) }.toMutableList()
        if (hiddenFound) matched += HIDDEN_TEXT_RULE
        if (matched.isEmpty()) return InputVerdict.Clean(sanitized)

        val score = matched.sumOf { it.weight }
        val labels = matched.map { it.label }.distinct()
        return if (score >= threshold) {
            InputVerdict.Blocked(labels, score)
        } else {
            InputVerdict.Suspicious(sanitized, labels, score)
        }
    }

    private data class Rule(val label: String, val pattern: Regex, val weight: Double = 1.0)

    private companion object {
        val ZERO_WIDTH = Regex("[\u200B-\u200F\u202A-\u202E\u2060\uFEFF]")
        val HTML_COMMENT = Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL)
        val HIDDEN_SPAN = Regex(
            "<span[^>]*style\\s*=\\s*[\"'][^\"']*(?:color\\s*:\\s*#f{3,6}|font-size\\s*:\\s*[01](?:\\.\\d+)?px)" +
                "[^\"']*[\"'][^>]*>(.*?)</span>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val HORIZONTAL_SPACE = Regex("[^\\S\\n]+")

        val HIDDEN_TEXT_RULE = Rule("скрытый текст", Regex("(?!)"), 1.5)

        /**
         * (?U) обязателен: без него в JVM `\w` — только ASCII, а IGNORE_CASE не
         * работает для кириллицы, и все русские правила молча не срабатывают.
         */
        private fun rx(pattern: String) = Regex("(?U)$pattern", RegexOption.IGNORE_CASE)

        /** Правила сгруппированы по меткам; вес 1.0, если не указано иное. */
        val RULES = listOf(
            // ── отмена/подмена инструкций ────────────────────────────────────
            Rule("override", rx("(забудь|игнорируй|игнорир\\w+|отбрось|не\\s+учитывай)\\s+(все|всё)?\\s*(предыдущ\\w+|прошл\\w+|прежн\\w+|верхн\\w+|выше)?\\s*(инструкц\\w+|указан\\w+|правил\\w+|ограничен\\w+)")),
            Rule("override", rx("ignore\\s+(all\\s+)?(the\\s+)?(above|previous|prior|preceding|any)")),
            Rule("override", rx("disregard\\s+(the\\s+)?(above|previous|prior|all)")),
            Rule("override", rx("(нов\\w+|друг\\w+|следующ\\w+)\\s+инструкц\\w+")),
            Rule("override", rx("(отныне|с\\s+этого\\s+момента)\\s+(ты|вы|действуй)")),
            Rule("override", rx("(отменил|отменен\\w+|отменён\\w*|устарел\\w+|аннулир\\w+)\\s+\\w*\\s*(инструкц\\w+|правил\\w+|разработчик\\w*)")),
            Rule("override", rx("from\\s+now\\s+on")),
            // ── попытка вытащить системный промпт и секреты ──────────────────
            Rule("extraction", rx("(повтори|процитируй|воспроизведи|выведи|напечатай|покажи|перескажи)[^.]{0,60}(дословно|систем\\w+\\s+промпт|промпт|инструкц\\w+|конфигурац\\w+|что\\s+написано|что\\s+выше)")),
            Rule("extraction", rx("(system\\s+prompt|original\\s+instructions?|initial\\s+instructions?|your\\s+instructions|internal\\s+rules)")),
            Rule("extraction", rx("what\\s+was\\s+written\\s+at\\s+the\\s+beginning")),
            Rule("extraction", rx("print\\s+the\\s+first\\s+\\d+\\s+lines")),
            Rule("extraction", rx("verbatim")),
            Rule("extraction", rx("(ключ\\w*\\s+интеграц\\w+|integration\\s+key|ключ\\w*\\s+доступа)")),
            Rule("extraction", rx("(внутренн\\w+|служебн\\w+)\\s+(правил\\w+|инструкц\\w+|ключ\\w*|данн\\w+)")),
            Rule("extraction", rx("выше\\s+этой\\s+строки")),
            // ── подмена персоны ──────────────────────────────────────────────
            Rule("role-play", rx("\\bDAN\\b|do\\s+anything\\s+now")),
            Rule("role-play", rx("developer\\s+mode|dan\\s+mode|jailbreak|режим\\s+разработчика")),
            Rule("role-play", rx("(ты\\s+теперь|теперь\\s+ты|представь,?\\s+что\\s+ты|притворись|сыграй|сыграем|сыграешь|войди\\s+в\\s+роль|act\\s+as|play\\s+the\\s+role|pretend)")),
            Rule("role-play", rx("(без\\s+ограничен\\w+|no\\s+(filters|restrictions|limits)|never\\s+refuses|нет\\s+ограничен\\w+)")),
            Rule("role-play", rx("бабушк\\w+|grandma|покойн\\w+\\s+(родств\\w+|бабушк\\w+|дедушк\\w+)")),
            // ── ложная ссылка на власть: «это сказал разработчик/система» ────
            Rule("ложный авторитет", Regex("\\bSYSTEM\\s*:")),
            Rule("ложный авторитет", rx("(я|мы)\\s+(разработчик\\w*|админ\\w*|из\\s+(службы|команды|безопасности)|сотрудник\\w*\\s+(банка|безопасност\\w+|поддержк\\w+|интеграц\\w+))")),
            Rule("ложный авторитет", rx("(для\\s+отладки|в\\s+целях\\s+отладки|для\\s+тестирован\\w+|debug\\s+mode)")),
            Rule("ложный авторитет", rx("(приоритетн\\w+|сервисн\\w+|служебн\\w+|систем\\w+)\\s+инструкц\\w+")),
            // ── подготовка утечки наружу ─────────────────────────────────────
            Rule("эксфильтрация", rx("https?://(?!(www\\.)?severnybank\\.ru)")),
            Rule("эксфильтрация", rx("(не\\s+сообщай|не\\s+упоминай|никому\\s+не\\s+(говори|рассказыв\\w+))")),
            Rule("эксфильтрация", rx("(отправ\\w+|передай|выгрузи|запости)[^.]{0,40}https?://")),
        )
    }
}

/**
 * Исход проверки входа. `sanitized` — текст без скрытых вставок: именно он
 * (а не оригинал) уходит в модель, если решение не Blocked.
 */
sealed interface InputVerdict {
    data class Clean(val sanitized: String) : InputVerdict
    data class Suspicious(val sanitized: String, val labels: List<String>, val score: Double) : InputVerdict
    data class Blocked(val labels: List<String>, val score: Double) : InputVerdict
}
