/**
 * Итог варианта A (монолитный промпт, один вызов). Sealed вместо исключений.
 * Done хранит и поля, которые монолит извлёк сам, — по ним кодовая проверка
 * R1–R6 выясняет, следовал ли монолит собственным правилам (само-сверка).
 */
sealed interface MonoResult {
    val metrics: CallMetrics

    data class Done(
        val decision: Decision,
        val message: String,
        /** null — монолит не вернул блок fields или вернул не-объект. */
        val fields: ClaimFields?,
        val raw: String,
        override val metrics: CallMetrics,
    ) : MonoResult

    data class Failed(
        val reason: String,
        override val metrics: CallMetrics = CallMetrics.ZERO,
    ) : MonoResult
}
