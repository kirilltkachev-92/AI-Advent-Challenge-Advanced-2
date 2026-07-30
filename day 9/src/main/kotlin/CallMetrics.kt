/**
 * Аккумулятор стоимости инференса: LLM-вызовы, токены, миллисекунды.
 * День 9 ведёт четыре такие суммы — по каждому из трёх этапов конвейера (B)
 * и по монолитному вызову (A) — чтобы отчёт честно показал, во сколько раз
 * декомпозиция дороже/дешевле одного большого промпта.
 */
data class CallMetrics(
    val llmCalls: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    operator fun plus(other: CallMetrics) = CallMetrics(
        llmCalls = llmCalls + other.llmCalls,
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        latencyMs = latencyMs + other.latencyMs,
    )

    companion object {
        val ZERO = CallMetrics()

        /** Метрики одного вызова LLM. */
        fun of(result: ChatResult) = CallMetrics(
            llmCalls = 1,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            latencyMs = result.latencyMs,
        )
    }
}
