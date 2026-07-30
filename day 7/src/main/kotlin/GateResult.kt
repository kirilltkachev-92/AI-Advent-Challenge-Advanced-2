/**
 * Итог прохождения кейса через InferenceGate — sealed-результат вместо исключений.
 * Accepted несёт извлечённое поручение и combined confidence = agreement × selfCheckConfidence
 * (обе оценки независимы: устойчивость к сэмплированию × уверенность проверяющего прохода).
 * Rejected — стадия и причина отказа: такие кейсы эскалируются человеку, а не угадываются.
 * baseline — метрики ПЕРВОГО извлекающего вызова кейса: столько стоил бы
 * гипотетический single-shot без контроля качества (для сравнения в отчёте).
 */
sealed interface GateResult {
    val retried: Boolean
    val metrics: CallMetrics
    val baseline: CallMetrics

    data class Accepted(
        val order: TransferOrder,
        val combinedConfidence: Double,
        val agreement: Double,
        val selfCheck: SelfCheck,
        override val retried: Boolean,
        override val metrics: CallMetrics,
        override val baseline: CallMetrics,
    ) : GateResult

    data class Rejected(
        val stage: String,
        val reason: String,
        override val retried: Boolean,
        override val metrics: CallMetrics,
        override val baseline: CallMetrics,
    ) : GateResult
}
