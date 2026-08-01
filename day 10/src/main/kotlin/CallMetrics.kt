/**
 * Аккумулятор стоимости инференса: LLM-вызовы, токены, миллисекунды.
 * День 10 ведёт две такие суммы — стоимость двухуровневого конвейера
 * (только fallback-вызовы; micro-model токенов не тратит) и стоимость
 * all-LLM-базлайна (вызов на каждый кейс) — чтобы отчёт честно показал
 * экономию «micro-first».
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
