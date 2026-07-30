import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Прогон кейсов через InferenceGate и отчёт output/report.md:
 * по-кейсовая таблица + итоги (принято/отклонено/retry, вызовы, токены, латентность)
 * + сравнение с single-shot baseline. Baseline не гипотеза из воздуха:
 * это фактические метрики первого извлекающего вызова каждого кейса —
 * ровно столько стоил бы инференс без контроля качества.
 */
class QualityRun(
    private val gate: InferenceGate,
    private val reportPath: Path = Path.of("output", "report.md"),
) {
    private val json = Json { prettyPrint = true }

    /** Все кейсы: строка прогресса на каждый + отчёт в markdown. */
    fun runAll(cases: List<TestCase>) {
        println("Прогон ${cases.size} кейсов через ворота качества (модель: ${Config.deepSeekModel()})…")
        val rows = cases.map { case ->
            val result = gate.evaluate(case.text)
            val line = "[%s] %-11s → %-6s conf=%s agreement=%s retry=%s вызовов=%d %d мс".format(
                Locale.ROOT,
                case.id, case.group, verdict(result), confidence(result), agreement(result),
                if (result.retried) "да" else "нет", result.metrics.llmCalls, result.metrics.latencyMs,
            )
            println(line)
            case to result
        }
        writeReport(rows)
        println("\nОтчёт записан: $reportPath")
    }

    /** Один ad-hoc текст: подробный вердикт в stdout (для демо). */
    fun runOne(text: String) {
        println("Текст: «$text»")
        println("Прогоняю через ворота качества…")
        val result = gate.evaluate(text)
        when (result) {
            is GateResult.Accepted -> {
                println("Вердикт: ACCEPT")
                println("Извлечено: ${json.encodeToString(result.order)}")
                println(
                    "Combined confidence: %.2f (agreement %.2f × self-check %.2f, status %s)"
                        .format(Locale.ROOT, result.combinedConfidence, result.agreement, result.selfCheck.confidence, result.selfCheck.status),
                )
                println("Self-check: ${result.selfCheck.reason}")
            }
            is GateResult.Rejected -> {
                println("Вердикт: REJECT — эскалация человеку")
                println("Стадия: ${result.stage}")
                println("Причина: ${result.reason}")
            }
        }
        println(
            "Метрики: %d LLM-вызовов, %d токенов, %d мс%s".format(
                Locale.ROOT,
                result.metrics.llmCalls, result.metrics.totalTokens, result.metrics.latencyMs,
                if (result.retried) " (был retry)" else "",
            ),
        )
    }

    // ── отчёт ────────────────────────────────────────────────────────────────

    private fun writeReport(rows: List<Pair<TestCase, GateResult>>) {
        val results = rows.map { it.second }
        val total = results.fold(CallMetrics.ZERO) { acc, r -> acc + r.metrics }
        val baseline = results.fold(CallMetrics.ZERO) { acc, r -> acc + r.baseline }
        val accepted = results.count { it is GateResult.Accepted }
        val rejected = results.size - accepted
        val retried = results.count { it.retried }

        val sb = StringBuilder()
        sb.appendLine("# День 7 — отчёт контроля качества инференса")
        sb.appendLine()
        sb.appendLine("Модель: `${Config.deepSeekModel()}`, дата: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.")
        sb.appendLine("Конвейер: redundancy ×3 (t=0.8, голосование) → constraints → self-check (t=0.0) → ACCEPT / RETRY ×1 / REJECT.")
        sb.appendLine()
        sb.appendLine("## Кейсы")
        sb.appendLine()
        sb.appendLine("| id | группа | вердикт | conf | agreement | self-check | retry | вызовы | токены | мс | ожидание |")
        sb.appendLine("|----|--------|---------|------|-----------|------------|-------|--------|--------|----|----------|")
        rows.forEach { (case, r) ->
            sb.appendLine(
                "| ${case.id} | ${case.group} | ${verdict(r)} | ${confidence(r)} | ${agreement(r)} | ${selfCheckCell(r)} " +
                    "| ${if (r.retried) "да" else "нет"} | ${r.metrics.llmCalls} | ${r.metrics.totalTokens} | ${r.metrics.latencyMs} | ${case.expected} |",
            )
        }
        sb.appendLine()
        sb.appendLine("## Итоги")
        sb.appendLine()
        sb.appendLine("- всего кейсов: ${results.size}")
        sb.appendLine("- принято (ACCEPT): $accepted")
        sb.appendLine("- отклонено (REJECT, эскалация человеку): $rejected")
        sb.appendLine("- потребовало retry (повторный полный конвейер): $retried")
        sb.appendLine("- LLM-вызовов всего: ${total.llmCalls}")
        sb.appendLine("- токенов всего: ${total.totalTokens} (prompt ${total.promptTokens} + completion ${total.completionTokens})")
        sb.appendLine(
            "- латентность: суммарно ${total.latencyMs} мс, в среднем на кейс ${total.latencyMs / results.size} мс",
        )
        sb.appendLine()
        sb.appendLine("## Цена контроля: сравнение с single-shot baseline")
        sb.appendLine()
        sb.appendLine("Baseline — 1 извлекающий вызов на кейс (фактические метрики первого вызова каждого кейса).")
        sb.appendLine()
        sb.appendLine("| метрика | baseline (1 вызов/кейс) | с контролем | множитель |")
        sb.appendLine("|---------|------------------------|-------------|-----------|")
        sb.appendLine("| LLM-вызовы | ${baseline.llmCalls} | ${total.llmCalls} | ${ratio(total.llmCalls.toDouble(), baseline.llmCalls.toDouble())} |")
        sb.appendLine("| токены | ${baseline.totalTokens} | ${total.totalTokens} | ${ratio(total.totalTokens.toDouble(), baseline.totalTokens.toDouble())} |")
        sb.appendLine("| латентность, мс | ${baseline.latencyMs} | ${total.latencyMs} | ${ratio(total.latencyMs.toDouble(), baseline.latencyMs.toDouble())} |")
        sb.appendLine()
        sb.appendLine(
            "Cost impact выражен множителем токенов: контроль качества стоит " +
                "${ratio(total.totalTokens.toDouble(), baseline.totalTokens.toDouble())} токенов и " +
                "${ratio(total.latencyMs.toDouble(), baseline.latencyMs.toDouble())} времени относительно single-shot — " +
                "цена за то, что неуверенные ответы отклоняются, а не двигают деньги наугад.",
        )

        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, sb.toString())
    }

    // ── форматирование ячеек ────────────────────────────────────────────────

    private fun verdict(r: GateResult): String = when (r) {
        is GateResult.Accepted -> "ACCEPT"
        is GateResult.Rejected -> "REJECT (${r.stage})"
    }

    private fun confidence(r: GateResult): String = when (r) {
        is GateResult.Accepted -> "%.2f".format(Locale.ROOT, r.combinedConfidence)
        is GateResult.Rejected -> "—"
    }

    private fun agreement(r: GateResult): String = when (r) {
        is GateResult.Accepted -> "%.2f".format(Locale.ROOT, r.agreement)
        is GateResult.Rejected -> "—"
    }

    private fun selfCheckCell(r: GateResult): String = when (r) {
        is GateResult.Accepted -> "${r.selfCheck.status} %.2f".format(Locale.ROOT, r.selfCheck.confidence)
        is GateResult.Rejected -> "—"
    }

    private fun ratio(actual: Double, base: Double): String =
        if (base <= 0) "—" else "%.1f×".format(Locale.ROOT, actual / base)
}
