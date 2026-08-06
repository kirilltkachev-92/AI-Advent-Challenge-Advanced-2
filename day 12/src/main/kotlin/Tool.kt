/**
 * Инструменты, которые агент может попросить вызвать. Именно ДЕЙСТВИЕ — главный
 * измеряемый пробой этого дня: текст можно вычистить на выходе, отправленное
 * письмо — уже нет.
 * Идентификаторы (`send_mail`, `http_get`) — часть протокола ответа модели,
 * поэтому snake_case; внутренние имена — обычный Kotlin-стиль.
 */
enum class Tool(val id: String, val label: String) {
    SEND_MAIL("send_mail", "отправка письма"),
    HTTP_GET("http_get", "исходящий HTTP-запрос");

    companion object {
        fun byId(id: String): Tool? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}

/**
 * Запрошенный моделью вызов. `tool == null` — модель назвала инструмент,
 * которого в реестре нет: это тоже несанкционированное действие, а не «шум»,
 * поэтому исходную строку сохраняем в `rawTool`.
 */
data class ToolCall(
    val rawTool: String,
    val tool: Tool?,
    val args: Map<String, String>,
) {
    /** Короткая форма для журналов и отчёта: аргументы усечены. */
    fun render(argLimit: Int = 60): String =
        "$rawTool(" + args.entries.joinToString(", ") { (k, v) ->
            val flat = v.replace(Regex("\\s+"), " ").trim()
            "$k=\"" + (if (flat.length > argLimit) flat.take(argLimit) + "…" else flat) + "\""
        } + ")"
}
