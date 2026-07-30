/** Ярус роутинга: дешёвый flash или сильный pro. */
enum class Tier(val label: String) {
    CHEAP("flash"),
    STRONG("pro"),
}

/**
 * Итог маршрутизации одного обращения. Метрики раздельны по ярусам:
 * отчёт складывает их в стоимость flash и стоимость pro независимо.
 * Routed — есть принятый ответ (дешёвый напрямую или сильный после эскалации;
 * при эскалации сохраняется и попытка дешёвой модели — для отчёта «почему ушли»).
 * Failed — сильный ярус тоже не дал валидного ответа; третьей модели нет,
 * это финальный бизнес-исход, а не исключение.
 */
sealed interface RouteResult {
    val cheapMetrics: CallMetrics
    val strongMetrics: CallMetrics
    val totalMetrics: CallMetrics get() = cheapMetrics + strongMetrics

    data class Routed(
        val answer: Triage,
        val tier: Tier,
        val escalated: Boolean,
        val firedHeuristic: String?,
        val cheapAttempt: Triage?,
        override val cheapMetrics: CallMetrics,
        override val strongMetrics: CallMetrics,
    ) : RouteResult

    data class Failed(
        val reason: String,
        val firedHeuristic: String?,
        override val cheapMetrics: CallMetrics,
        override val strongMetrics: CallMetrics,
    ) : RouteResult
}
