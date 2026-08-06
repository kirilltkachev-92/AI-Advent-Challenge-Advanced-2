import java.text.Normalizer

/**
 * Эшелон 1: input sanitization. Офлайн, без LLM, детерминированно.
 *
 * Принцип один: **модель должна видеть ровно то, что видит человек**. Всё, что
 * в отрендеренном виде невидимо (комментарии разметки, скрывающие стили,
 * zero-width символы, title у ссылки), — это не данные, а канал для закладки,
 * и в модель оно не уходит.
 *
 * Из того же принципа следует и обратное требование, не менее важное: ВИДИМЫЙ
 * контент трогать нельзя. Поэтому стиль элемента разбирается по объявлениям, а
 * не подстрокой: `font-size:16px` и `opacity:0.85` — это обычный текст, и
 * жадный шаблон, который съел бы их вместе с `font-size:1px`, резал бы пользу
 * (регресс ловит фикстура `style-fixture`).
 *
 * Контракт: `clean(raw)` → `Sanitized(visibleText, removed)`. `removed` — не
 * побочный лог, а главный артефакт дня: он показывает, ЧТО именно было спрятано
 * и какой техникой, и на нём строятся и отчёт, и команда `sanitize`.
 *
 * Порядок принципиален: невидимые символы снимаются ПЕРВЫМИ, до вырезания
 * контейнеров, — иначе zero-width внутри HTML-комментария уехал бы вместе с
 * комментарием, и отчёт не показал бы вторую технику.
 */
class InputSanitizer {

    fun clean(raw: String): Sanitized {
        val removed = mutableListOf<RemovedFragment>()
        var text = raw

        // 1. Невидимые символы: zero-width, word joiner, BOM, мягкий перенос.
        val invisible = INVISIBLE.findAll(text).count()
        if (invisible > 0) {
            val at = INVISIBLE.find(text)!!.range.first
            val context = INVISIBLE.replace(text.substring(maxOf(0, at - 40), minOf(text.length, at + 80)), "")
            removed += RemovedFragment(
                technique = "zero-width символы ($invisible шт.)",
                excerpt = excerpt(context),
                note = "сами символы невидимы; в excerpt — контекст первого вхождения уже без них",
            )
            text = INVISIBLE.replace(text, "")
        }

        // 2. HTML-комментарии — классический канал indirect injection в почте и вебе.
        text = HTML_COMMENT.replace(text) { m ->
            removed += RemovedFragment("HTML-комментарий", excerpt(m.groupValues[1]))
            ""
        }

        // 3. Элементы со скрывающим стилем. Решение принимает hidingDeclaration()
        //    по разобранным объявлениям, поэтому видимые стили остаются нетронутыми.
        text = STYLED_ELEMENT.replace(text) { m ->
            val hiding = hidingDeclaration(m.groupValues[2])
            if (hiding == null) {
                m.value
            } else {
                removed += RemovedFragment("скрывающий стиль ($hiding)", excerpt(m.groupValues[3]))
                ""
            }
        }

        // 4. Markdown-ссылка: title невидим в рендере, поэтому срезается целиком,
        //    видимый текст и сам URL остаются — иначе терялась бы польза.
        text = MD_LINK_TITLE.replace(text) { m ->
            removed += RemovedFragment("title у markdown-ссылки", excerpt(m.groupValues[3]))
            "[${m.groupValues[1]}](${m.groupValues[2]})"
        }

        // 5. NFKC: снимает подмену символов гомоглифами и «широкими» формами.
        text = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return Sanitized(collapse(text), removed)
    }

    // ── помощники ────────────────────────────────────────────────────────────

    /**
     * Разбирает inline-стиль по объявлениям и возвращает первое, которое делает
     * элемент невидимым, либо null. Значение сверяется целиком (`0`, `1px`,
     * `none`), поэтому `font-size:16px`, `opacity:0.85` и `color:#333333`
     * скрывающими не считаются.
     */
    private fun hidingDeclaration(style: String): String? = style.split(";")
        .mapNotNull { declaration ->
            val idx = declaration.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val property = declaration.substring(0, idx).trim().lowercase()
            val value = declaration.substring(idx + 1).trim().lowercase()
            val hides = when (property) {
                "color" -> value in setOf("#fff", "#ffffff", "white")
                "font-size" -> FONT_SIZE_HIDING.matches(value)
                "display" -> value == "none"
                "visibility" -> value == "hidden"
                "opacity" -> OPACITY_HIDING.matches(value)
                else -> false
            }
            if (hides) "$property:$value" else null
        }
        .firstOrNull()

    private fun excerpt(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > EXCERPT_LIMIT) flat.take(EXCERPT_LIMIT) + "…" else flat
    }

    /**
     * Схлопывает подряд идущие пробелы ВНУТРИ строки в один, сохраняя отступ
     * строки и переводы строк, и сжимает три и более пустых строки до одной.
     * Отступ сохраняется намеренно: источником бывает код, и превращать его в
     * простыню — значит ломать пользу ради косметики.
     */
    private fun collapse(text: String): String = text
        .lines().joinToString("\n") { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            indent + line.drop(indent.length).replace(HORIZONTAL_SPACE, " ").trimEnd()
        }
        .replace(BLANK_LINES, "\n\n")
        .trim()

    private companion object {
        const val EXCERPT_LIMIT = 120

        val INVISIBLE = Regex("[\u200B-\u200D\u2060\uFEFF\u00AD\u200E\u200F]")
        val HTML_COMMENT = Regex("<!--([\\s\\S]*?)-->")

        /**
         * Любой элемент с inline-стилем; скрывающий он или нет, решает
         * `hidingDeclaration`. Backreference `\\1` закрывает тег тем же именем:
         * без него вложенная разметка съедала бы половину документа.
         */
        val STYLED_ELEMENT = Regex(
            "<(span|div|p|section)\\b[^>]*style\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>([\\s\\S]*?)</\\1>",
            RegexOption.IGNORE_CASE,
        )

        /** Кегль, при котором текст физически нечитаем: 0, 1, 0.5px и т.п. */
        val FONT_SIZE_HIDING = Regex("[01](?:\\.\\d+)?(?:px|pt)?")
        val OPACITY_HIDING = Regex("0(?:\\.0+)?")

        val MD_LINK_TITLE = Regex("\\[([^\\]]*)\\]\\(\\s*(\\S+?)\\s+[\"']([^\"']*)[\"']\\s*\\)")
        val HORIZONTAL_SPACE = Regex("[^\\S\\n]{2,}")
        val BLANK_LINES = Regex("\n{3,}")
    }
}

/**
 * Что именно вырезал эшелон 1. `technique` — человекочитаемое имя техники (оно
 * же идёт в отчёт и в демо), `excerpt` — до 120 символов спрятанного текста.
 */
data class RemovedFragment(
    val technique: String,
    val excerpt: String,
    val note: String? = null,
)

/** Результат очистки: то, что видит человек, и полный список спрятанного. */
data class Sanitized(
    val visibleText: String,
    val removed: List<RemovedFragment>,
)
