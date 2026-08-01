/**
 * Sealed-итог двухуровневого конвейера: кто дал финальную метку.
 * Общий контракт: финальная метка + полный MicroResult (метка/score/статус
 * уровня 1 нужны отчёту в обоих случаях) + цена LLM (для micro — ноль)
 * + суммарная латентность. Латентность micro хранится в микросекундах —
 * ~0–1 мс против сотен мс у LLM, в этом и смысл дня.
 */
sealed interface RouteResult {
    val micro: MicroResult
    val finalLabel: Intent
    val llmMetrics: CallMetrics

    /** Уровень, давший ответ, — колонка «level» в отчёте. */
    val level: String get() = if (this is MicroHandled) "micro" else "llm"

    /** Полная латентность конвейера на кейсе, мс (micro-часть почти всегда 0). */
    val totalLatencyMs: Long get() = micro.latencyMicros / 1_000 + llmMetrics.latencyMs

    /** Уровень 1 уверен (OK) — LLM не вызывалась вовсе. */
    data class MicroHandled(override val micro: MicroResult) : RouteResult {
        override val finalLabel: Intent get() = micro.label
        override val llmMetrics: CallMetrics get() = CallMetrics.ZERO
    }

    /** Уровень 1 вернул UNSURE — метку дал LLM-fallback (или его безопасный дефолт). */
    data class LlmHandled(override val micro: MicroResult, val fallback: LlmOutcome) : RouteResult {
        override val finalLabel: Intent get() = fallback.intent
        override val llmMetrics: CallMetrics get() = fallback.metrics
    }
}
