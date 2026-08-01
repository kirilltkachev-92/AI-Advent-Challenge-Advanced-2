import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Прогон серии кейсов и отчёт output/report.md. Каждый кейс проходит:
 *  1) двухуровневый конвейер (micro → при UNSURE fallback-LLM);
 *  2) all-LLM-базлайн — тот же классификатор LLM напрямую (1 вызов на кейс),
 *     его вызовы в стоимость конвейера НЕ входят — это контрольная группа.
 * Отчёт: по-кейсовая таблица, итоги (micro-handled / fallback / вызовы LLM),
 * три точности (конвейер, all-LLM, micro-принудительно), стоимость и экономия.
 * Команда `one` — подробный разбор одного текста; `micro` — вся серия только
 * уровнем 1, офлайн, без единого LLM-вызова (тюнинг порога и демо скорости).
 */
class SeriesRun(
    pipelineFactory: () -> TwoTierPipeline = { TwoTierPipeline() },
    baselineFactory: () -> LlmClassifier = { LlmClassifier() },
    private val micro: MicroClassifier = MicroClassifier(),
    private val reportPath: Path = Path.of("output", "report.md"),
) {
    // Лениво: офлайн-команда `micro` не должна требовать DEEPSEEK_API_KEY.
    private val pipeline by lazy(pipelineFactory)
    private val baseline by lazy(baselineFactory)

    /** Вся серия: конвейер + базлайн, строка прогресса на кейс, отчёт в markdown. */
    fun runAll(cases: List<QueryCase>) {
        println("Серия из ${cases.size} кейсов: конвейер micro→LLM + all-LLM-базлайн (${baseline.model})…")
        val rows = cases.map { case ->
            val route = pipeline.route(case.text)
            val base = baseline.classify(case.text)
            val row = CaseRow(case, route, base)
            println(progressLine(row))
            row
        }
        writeReport(rows)
    }

    /** Один ad-hoc текст: токены, близости, маршрут, при fallback — сырой ответ LLM. */
    fun runOne(text: String) {
        println("Запрос: «$text»")
        val m = micro.classify(text)
        println("\n── Уровень 1: micro-model (TF-IDF nearest centroid, без сети) ──")
        println("Токены после стемминга: ${m.tokens} (знакомых словарю: ${m.knownTokens})")
        println("Близость к центроидам интентов:")
        m.sims.forEach { println("  %-12s %.3f".format(Locale.ROOT, it.intent, it.similarity)) }
        println(
            "top1=%.3f, отрыв (margin)=%.3f → score=%.3f (порог %.2f, пол top1 %.2f, мин. знакомых токенов %d)"
                .format(
                    Locale.ROOT, m.sims[0].similarity, m.margin, m.score,
                    Config.confidenceThreshold(), MicroClassifier.SIM_FLOOR, MicroClassifier.MIN_KNOWN_TOKENS,
                ),
        )
        println("Статус: ${m.status}, латентность: ${m.latencyMicros} мкс")

        when (val route = pipeline.route(text)) {
            is RouteResult.MicroHandled ->
                println("\nМаршрут: micro-model уверена → интент ${route.finalLabel} БЕЗ вызова LLM (0 токенов)")
            is RouteResult.LlmHandled -> {
                println("\nМаршрут: UNSURE → уровень 2, LLM-fallback (${baseline.model})")
                val f = route.fallback
                println("Сырой ответ LLM: ${f.raw}${if (f.retried) " (после retry)" else ""}")
                val validity = if (f.formatValid) "формат валиден" else "формат INVALID → безопасный дефолт OPERATOR"
                println("Интент: ${f.intent} ($validity)")
                println("Цена fallback: ${f.metrics.llmCalls} вызов(а), ${f.metrics.totalTokens} ток., ${f.metrics.latencyMs} мс")
            }
        }
    }

    /** Серия только уровнем 1 — офлайн-проверка без LLM: точность и доля OK. */
    fun runMicroOnly(cases: List<QueryCase>) {
        println("Только micro-model, без сети (${cases.size} кейсов):")
        var ok = 0
        var hitAll = 0
        var hitOk = 0
        cases.forEach { case ->
            val m = micro.classify(case.text)
            if (m.status == MicroStatus.OK) ok++
            if (m.label == case.expected) hitAll++
            if (m.status == MicroStatus.OK && m.label == case.expected) hitOk++
            println(
                "[%s] %-12s score=%.3f top1=%.3f margin=%.3f known=%d %-6s (ожид. %-12s) %s"
                    .format(
                        Locale.ROOT, case.id, m.label, m.score, m.sims[0].similarity, m.margin,
                        m.knownTokens, m.status, case.expected, mark(m.label == case.expected),
                    ),
            )
        }
        println(
            "\nOK: $ok/${cases.size}; точность принудительной micro-метки: $hitAll/${cases.size}; " +
                "верных среди OK: $hitOk/$ok",
        )
    }

    // ── отчёт ────────────────────────────────────────────────────────────────

    private fun writeReport(rows: List<CaseRow>) {
        val total = rows.size
        val microHandled = rows.count { it.route is RouteResult.MicroHandled }
        val fallbackCount = total - microHandled
        val pipelineMetrics = rows.fold(CallMetrics.ZERO) { acc, r -> acc + r.route.llmMetrics }
        val baselineMetrics = rows.fold(CallMetrics.ZERO) { acc, r -> acc + r.base.metrics }
        val pipelineLatency = rows.sumOf { it.route.totalLatencyMs }

        val pipelineHits = rows.count { it.route.finalLabel == it.case.expected }
        val baselineHits = rows.count { it.base.intent == it.case.expected }
        val microForcedHits = rows.count { it.route.micro.label == it.case.expected }
        val invalidCount = rows.count { (it.route as? RouteResult.LlmHandled)?.fallback?.formatValid == false }

        val sb = StringBuilder()
        sb.appendLine("# День 10 — отчёт: micro-model first vs all-LLM")
        sb.appendLine()
        sb.appendLine(
            "Fallback/базлайн: `${baseline.model}`; порог уверенности micro: " +
                "%.2f (пол top1 %.2f, мин. знакомых токенов %d); дата: %s."
                    .format(
                        Locale.ROOT, Config.confidenceThreshold(), MicroClassifier.SIM_FLOOR,
                        MicroClassifier.MIN_KNOWN_TOKENS, now(),
                    ),
        )
        sb.appendLine(
            "Уверенность micro-model структурная (близость + отрыв top1−top2), а не самооценка: " +
                "маленькие модели рапортуют о 100% уверенности (эффект Даннинга–Крюгера), " +
                "верить их самоотчёту нельзя.",
        )
        sb.appendLine()
        sb.appendLine("## Кейсы")
        sb.appendLine()
        sb.appendLine("| id | кейс | ожидание | micro: метка (score, статус) | финал | уровень | верно | мс | базлайн LLM | базлайн верно |")
        sb.appendLine("|----|------|----------|------------------------------|-------|---------|-------|----|-------------|---------------|")
        rows.forEach { r ->
            val m = r.route.micro
            sb.appendLine(
                "| ${r.case.id} | ${r.case.brief} | ${r.case.expected} " +
                    "| ${m.label} (%.2f, ${m.status}) ".format(Locale.ROOT, m.score) +
                    "| ${r.route.finalLabel} | ${r.route.level} " +
                    "| ${mark(r.route.finalLabel == r.case.expected)} | ${r.route.totalLatencyMs} " +
                    "| ${r.base.intent} | ${mark(r.base.intent == r.case.expected)} |",
            )
        }
        sb.appendLine()
        sb.appendLine("## Итоги")
        sb.appendLine()
        sb.appendLine("- micro-model закрыла сама: **$microHandled/$total**; ушло в LLM-fallback: **$fallbackCount/$total**")
        sb.appendLine(
            "- вызовов большой LLM в конвейере: **${pipelineMetrics.llmCalls}** " +
                "(fallback${if (pipelineMetrics.llmCalls > fallbackCount) ", включая retry на невалидный формат" else ""}; " +
                "${baselineMetrics.llmCalls} вызовов базлайна — контрольная группа, в стоимость конвейера не входят)",
        )
        if (invalidCount > 0) {
            sb.appendLine("- невалидный формат fallback после retry (→ безопасный дефолт OPERATOR): $invalidCount")
        }
        sb.appendLine("- точность конвейера micro→LLM: **$pipelineHits/$total**")
        sb.appendLine("- точность all-LLM-базлайна: **$baselineHits/$total**")
        sb.appendLine("- точность micro-model принудительно на всех кейсах (если бы fallback не было): $microForcedHits/$total")
        sb.appendLine()
        sb.appendLine("## Стоимость")
        sb.appendLine()
        sb.appendLine("| вариант | вызовы LLM | токены (prompt+completion) | латентность всего, мс | средняя на кейс, мс |")
        sb.appendLine("|---------|------------|----------------------------|-----------------------|---------------------|")
        sb.appendLine(
            "| конвейер micro→LLM | ${pipelineMetrics.llmCalls} | ${tokens(pipelineMetrics)} " +
                "| $pipelineLatency | ${pipelineLatency / total} |",
        )
        sb.appendLine(
            "| all-LLM-базлайн | ${baselineMetrics.llmCalls} | ${tokens(baselineMetrics)} " +
                "| ${baselineMetrics.latencyMs} | ${baselineMetrics.latencyMs / total} |",
        )
        sb.appendLine()
        sb.appendLine("## Вывод")
        sb.appendLine()
        sb.appendLine(
            "- вызовы большой LLM срезаны с $total до ${pipelineMetrics.llmCalls}; " +
                "средняя латентность ${pipelineLatency / total} мс против ${baselineMetrics.latencyMs / total} мс у all-LLM " +
                "(экономия токенов: ${baselineMetrics.totalTokens} → ${pipelineMetrics.totalTokens}, " +
                "%.0f%%)".format(
                    Locale.ROOT,
                    100.0 * (baselineMetrics.totalTokens - pipelineMetrics.totalTokens) / maxOf(1, baselineMetrics.totalTokens),
                ),
        )
        sb.appendLine(
            "- точность при этом $pipelineHits/$total против $baselineHits/$total у all-LLM: " +
                "micro-model берёт только кейсы, где уверена структурно (score = top1·(1+margin)/2), " +
                "а сомнительное честно отдаёт большой модели",
        )
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, sb.toString())
        println("\nОтчёт записан: $reportPath")
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun progressLine(row: CaseRow): String {
        val m = row.route.micro
        return "[%s] micro=%-12s score=%.2f %-6s → финал=%-12s (%-5s) %s базлайн=%-12s %s (ожид. %-12s) мс=%d".format(
            Locale.ROOT,
            row.case.id, m.label, m.score, m.status,
            row.route.finalLabel, row.route.level, mark(row.route.finalLabel == row.case.expected),
            row.base.intent, mark(row.base.intent == row.case.expected),
            row.case.expected, row.route.totalLatencyMs,
        )
    }

    private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun mark(hit: Boolean): String = if (hit) "✓" else "✗"

    private fun tokens(m: CallMetrics): String = "${m.totalTokens} (${m.promptTokens}+${m.completionTokens})"
}

/** Кейс + маршрут конвейера + исход базлайна — строка сравнения. */
private data class CaseRow(val case: QueryCase, val route: RouteResult, val base: LlmOutcome)
