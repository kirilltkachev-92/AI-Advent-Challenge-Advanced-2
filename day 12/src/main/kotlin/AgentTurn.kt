/**
 * Один ход агента: что реально ушло в модель, что она вернула, какие действия
 * попросила и что с ними сделала политика, что увидел пользователь.
 *
 * Различает три разных «ответа», и путать их нельзя: `rawResponse` — сырой JSON
 * модели (идёт в транскрипт как доказательство), `answer` — разобранное поле
 * `answer` до эшелона 3, ИМЕННО ЕГО судит `BreachDetector`, `finalAnswer` — то,
 * что дошло до пользователя. Расхождение `answer` и `finalAnswer` и есть вклад
 * эшелона 3.
 */
data class AgentTurn(
    val mode: DefenseMode,
    val style: PayloadStyle,
    val systemPrompt: String,
    val userBlock: String,
    val rawResponse: String,
    val answer: String,
    /** false — модель вернула не JSON: ответ взят как сырой текст, действий нет. */
    val parsed: Boolean,
    /** Пояснение к парсингу: причина retry или отказа от разбора; null — всё чисто. */
    val parseNote: String?,
    /** Действия, которые агент ПОПРОСИЛ вызвать (до политики). */
    val requestedCalls: List<ToolCall>,
    /** Журнал шины: решение политики по каждому вызову. */
    val toolRecords: List<ToolCallRecord>,
    /** Что агент сам пометил как попытку им поуправлять (только hardened-режим). */
    val suspicious: List<String>,
    /** Что вырезал эшелон 1 по всем источникам; пусто в naive. */
    val removed: List<RemovedFragment>,
    /** null — эшелон 3 в этом режиме выключен. */
    val outputVerdict: OutputVerdict?,
    val finalAnswer: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    val executedCalls: List<ToolCallRecord> get() = toolRecords.filter { it.allowed }
    val blockedCalls: List<ToolCallRecord> get() = toolRecords.filterNot { it.allowed }

    /** Причины эшелона 3 — пусто, если он выключен или ничего не нашёл. */
    val outputReasons: List<String>
        get() = when (val v = outputVerdict) {
            null, is OutputVerdict.Pass -> emptyList()
            is OutputVerdict.Redacted -> v.reasons
            is OutputVerdict.Blocked -> v.reasons
        }
}
