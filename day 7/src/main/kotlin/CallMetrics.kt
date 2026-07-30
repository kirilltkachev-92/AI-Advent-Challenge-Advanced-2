/**
 * Аккумулятор стоимости инференса: сколько LLM-вызовов, токенов и миллисекунд
 * потратил кейс. Складывается оператором `+` по мере прохождения пайплайна;
 * в отчёте сравнивается с гипотетическим single-shot baseline (1 вызов на кейс).
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
