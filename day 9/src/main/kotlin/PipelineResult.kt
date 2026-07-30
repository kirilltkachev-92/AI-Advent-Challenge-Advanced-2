/**
 * Итог варианта B (конвейер из трёх этапов). Sealed вместо исключений:
 * успешный проход хранит сырые выходы и метрики КАЖДОГО этапа — отчёту нужны
 * разбор стоимости по этапам и сверка решения этапа 2 с кодовой проверкой правил.
 * Failed указывает, какой именно этап сломался, — в монолите такой границы нет.
 */
sealed interface PipelineResult {
    val stage1Metrics: CallMetrics
    val stage2Metrics: CallMetrics
    val stage3Metrics: CallMetrics
    val totalMetrics: CallMetrics get() = stage1Metrics + stage2Metrics + stage3Metrics

    data class Done(
        val fields: ClaimFields,
        val stage1Raw: String,
        val stage2Decision: RuleDecision,
        val stage2Raw: String,
        val finalDecision: Decision,
        val message: String,
        val stage3Raw: String,
        override val stage1Metrics: CallMetrics,
        override val stage2Metrics: CallMetrics,
        override val stage3Metrics: CallMetrics,
    ) : PipelineResult

    data class Failed(
        val stage: String,
        val reason: String,
        /** Поля этапа 1, если он успел отработать, — для кодовой проверки даже при падении дальше. */
        val fields: ClaimFields? = null,
        override val stage1Metrics: CallMetrics = CallMetrics.ZERO,
        override val stage2Metrics: CallMetrics = CallMetrics.ZERO,
        override val stage3Metrics: CallMetrics = CallMetrics.ZERO,
    ) : PipelineResult
}
