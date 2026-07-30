import java.util.Locale

/**
 * Ворота приёмки инференса: чистая оркестрация трёх подходов контроля качества
 * над одним текстом поручения. Конвейер одной попытки:
 *   redundancy ×3 (голосование) → constraints (жёсткие правила) → self-check (второй проход LLM).
 * Правило приёмки: self-check OK/UNSURE, confidence ≥ 0.6, agreement ≥ 2/3 → ACCEPT
 * (единогласие и высокая уверенность отражаются в combined confidence, а не отдельной веткой);
 * иначе → один RETRY всего конвейера с ужесточённым промптом; снова мимо → REJECT.
 * Combined confidence = agreement × selfCheckConfidence — произведение независимых
 * оценок устойчивости и корректности. Ошибка здесь — неверное движение денег,
 * поэтому любой сомнительный кейс отклоняется (эскалация человеку), а не угадывается.
 */
class InferenceGate(
    private val redundancy: RedundancyRunner,
    private val constraints: ConstraintChecker,
    private val selfChecker: SelfChecker,
) {
    fun evaluate(text: String): GateResult {
        val first = attempt(text, strict = false)
        if (first is Attempt.Passed) return first.toAccepted(retried = false, totalMetrics = first.metrics, baseline = first.firstCall)

        // Один повтор всего конвейера с ужесточённым промптом; baseline остаётся от первого вызова.
        val failedFirst = first as Attempt.Failed
        val second = attempt(text, strict = true)
        val totalMetrics = first.metrics + second.metrics
        return when (second) {
            is Attempt.Passed -> second.toAccepted(retried = true, totalMetrics = totalMetrics, baseline = failedFirst.firstCall)
            is Attempt.Failed -> GateResult.Rejected(
                stage = second.stage,
                reason = second.reason,
                retried = true,
                metrics = totalMetrics,
                baseline = failedFirst.firstCall,
            )
        }
    }

    /** Одна попытка конвейера: redundancy → constraints → self-check. */
    private fun attempt(text: String, strict: Boolean): Attempt {
        val round = redundancy.run(text, strict)
        var metrics = round.perCall.fold(CallMetrics.ZERO, CallMetrics::plus)
        val firstCall = round.perCall.first()

        val majority = when (val vote = round.vote) {
            is RedundancyVote.NoMajority -> return Attempt.Failed("redundancy", vote.reason, metrics, firstCall)
            is RedundancyVote.Majority -> vote
        }

        val violations = constraints.check(majority.order)
        if (violations.isNotEmpty()) {
            return Attempt.Failed("constraints", violations.joinToString("; "), metrics, firstCall)
        }

        val outcome = selfChecker.check(text, majority.order)
        metrics += outcome.metrics
        val sc = outcome.check
        val accepted = (sc.status == SelfCheckStatus.OK || sc.status == SelfCheckStatus.UNSURE) &&
            sc.confidence >= 0.6 && majority.votes >= 2
        if (!accepted) {
            val reason = "self-check: status=${sc.status}, confidence=%.2f — %s"
                .format(Locale.ROOT, sc.confidence, sc.reason)
            return Attempt.Failed("self-check", reason, metrics, firstCall)
        }
        return Attempt.Passed(majority.order, majority.agreement, sc, metrics, firstCall)
    }

    /** Промежуточный итог одной попытки; firstCall — оценка single-shot baseline. */
    private sealed interface Attempt {
        val metrics: CallMetrics
        val firstCall: CallMetrics

        data class Passed(
            val order: TransferOrder,
            val agreement: Double,
            val selfCheck: SelfCheck,
            override val metrics: CallMetrics,
            override val firstCall: CallMetrics,
        ) : Attempt

        data class Failed(
            val stage: String,
            val reason: String,
            override val metrics: CallMetrics,
            override val firstCall: CallMetrics,
        ) : Attempt
    }

    private fun Attempt.Passed.toAccepted(retried: Boolean, totalMetrics: CallMetrics, baseline: CallMetrics) =
        GateResult.Accepted(
            order = order,
            combinedConfidence = agreement * selfCheck.confidence,
            agreement = agreement,
            selfCheck = selfCheck,
            retried = retried,
            metrics = totalMetrics,
            baseline = baseline,
        )
}
