/**
 * Помощники «как спрятать закладку в данных». Вынесены отдельно, чтобы все
 * источники собирались одной техникой на всех, и в отчёте можно было честно
 * сказать, какая техника где применена (а не искать глазами в исходнике).
 *
 * Все четыре техники задания: HTML-комментарий, белый текст на белом фоне,
 * zero-width символы, markdown-ссылка с payload в title. `displayNone` — пятая,
 * добавлена для сценария `web-search`: агрегаторы прячут блоки именно так.
 */
object HiddenText {

    /** Zero-width space: не рендерится ни в одном вьюере, но живёт в тексте. */
    const val ZWSP = "\u200B"

    /** Невидим в письме и в отрендеренном markdown, но целиком уходит в модель. */
    fun htmlComment(payload: String): String = "<!-- $payload -->"

    /** Белым по белому и кеглем 1px: человек не видит, парсер видит. */
    fun whiteOnWhite(payload: String): String =
        "<span style=\"color:#ffffff;font-size:1px\">$payload</span>"

    /** Блок, который браузер не рисует вовсе. */
    fun displayNone(payload: String): String =
        "<div style=\"display:none\">$payload</div>"

    /**
     * Payload в title markdown-ссылки: в отрендеренном виде это всплывающая
     * подсказка, а в исходнике — обычный текст, который читает модель.
     */
    fun markdownLinkTitle(text: String, url: String, payload: String): String =
        "[$text]($url \"$payload\")"

    /**
     * Разбавляет слова zero-width пробелами: текст выглядит нетронутым, но
     * ломает наивные keyword-фильтры («игнорируй» перестаёт совпадать с
     * `игнорируй`). Токены с `://`, `@` и обратной кавычкой пропускаются
     * намеренно: невидимка внутри URL сделала бы ссылку нерабочей, и стенд
     * мерил бы не пробой, а собственную опечатку.
     */
    fun zeroWidth(payload: String): String = payload.split(" ").joinToString(" ") { token ->
        if (token.length < 4 || token.contains("://") || token.contains("@") || token.contains("`")) {
            token
        } else {
            token.take(2) + ZWSP + token.drop(2)
        }
    }
}
