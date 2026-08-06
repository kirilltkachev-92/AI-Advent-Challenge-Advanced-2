/**
 * Реестр инструментов и журнал вызовов.
 *
 * ВАЖНО (безопасность стенда): все приёмники ФЕЙКОВЫЕ. `send_mail` ничего не
 * отправляет и возвращает `"queued"`, `http_get` никуда не ходит и возвращает
 * `"200 OK (заглушка)"`. Реальной сети здесь нет вовсе — ни одного сокета, ни
 * одного SMTP-клиента; все домены в закладках лежат в зарезервированном TLD
 * `*.example`, который по RFC 6761 не резолвится. Стенд мерит НАМЕРЕНИЕ агента
 * («попросил вызвать») и решение политики («дали или нет»), а не последствия.
 *
 * Контракт: `execute(call, policy)` всегда возвращает запись и всегда пишет её
 * в журнал — в том числе для запрещённых вызовов. Запрет должен быть виден в
 * отчёте, а не выглядеть как «модель ничего не просила».
 */
class ToolBus {
    private val log = mutableListOf<ToolCallRecord>()

    /** Журнал в порядке поступления: и разрешённые, и заблокированные вызовы. */
    val records: List<ToolCallRecord> get() = log.toList()

    fun execute(call: ToolCall, policy: ToolPolicy): ToolCallRecord {
        val record = when {
            call.tool == null -> ToolCallRecord(
                call = call,
                allowed = false,
                reason = "инструмента «${call.rawTool}» нет в реестре",
                result = null,
            )
            !policy.enforced -> ToolCallRecord(
                call = call,
                allowed = true,
                reason = "политика выключена (${policy.label}) — вызов исполнен как есть",
                result = fakeSink(call.tool),
            )
            call.tool in policy.allowed -> ToolCallRecord(
                call = call,
                allowed = true,
                reason = "пользователь запросил это действие",
                result = fakeSink(call.tool),
            )
            else -> ToolCallRecord(
                call = call,
                allowed = false,
                reason = "пользователь не просил «${call.tool.id}»; разрешено: " +
                    (policy.allowed.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.id } ?: "ничего"),
                result = null,
            )
        }
        log += record
        return record
    }

    /** Фейковый приёмник: строка-заглушка вместо реального эффекта. */
    private fun fakeSink(tool: Tool): String = when (tool) {
        Tool.SEND_MAIL -> "queued"
        Tool.HTTP_GET -> "200 OK (заглушка)"
    }
}

/**
 * Политика вызова инструментов. `enforced = false` — режимы без эшелона 3:
 * агент делает всё, что придумал, и именно это надо показать в отчёте.
 */
data class ToolPolicy(
    val allowed: Set<Tool>,
    val enforced: Boolean,
    val label: String,
)

/** Запись журнала: что просили, разрешили ли, почему и что вернул фейковый приёмник. */
data class ToolCallRecord(
    val call: ToolCall,
    val allowed: Boolean,
    val reason: String,
    /** null — вызов не исполнялся. */
    val result: String?,
)
