import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Прогон серии кейсов ОБОИМИ вариантами и отчёт output/report.md:
 * по-кейсовая таблица (решения A/B против разметки, сверка этапа 2 с кодовой
 * проверкой правил, токены и латентность) + итоги: точность A vs B, частота
 * расхождений LLM-решения с детерминированным кодом (для B — этап 2, для A —
 * финальное решение против правил по собственным полям A), стоимость B
 * с разбивкой по этапам и честное «во сколько раз B дороже».
 */
class CompareRun(
    private val mono: MonolithicRunner = MonolithicRunner(),
    private val pipeline: StagePipeline = StagePipeline(),
    private val reportPath: Path = Path.of("output", "report.md"),
) {
    /** Вся серия: строка прогресса на каждый кейс + отчёт в markdown. */
    fun runAll(cases: List<ClaimCase>) {
        val rows = collect(cases)
        val sb = StringBuilder()
        sb.appendLine("# День 9 — отчёт: монолит (A) vs конвейер из трёх этапов (B)")
        sb.appendLine()
        sb.appendLine("Модели: ${modelsLine()}; дата: ${now()}.")
        sb.appendLine(
            "Оба варианта получают идентичные правила R1–R6 и требуют одну форму финального JSON; " +
                "«этап 2 vs код» — сверка LLM-решения с детерминированной функцией DecisionRules.decide (LLM решает, код проверяет).",
        )
        sb.append(reportBody(rows, "##"))
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, sb.toString())
        println("\nОтчёт записан: $reportPath")
    }

    /**
     * Микс-прогон: те же кейсы, но конвейер B на смешанных моделях (по этапу).
     * Секция ДОПИСЫВАЕТСЯ в конец существующего report.md (повторный запуск
     * заменяет прежнюю микс-секцию); базовый отчёт должен быть уже создан `run`.
     * Монолит A прогоняется заново — соотношения внутри секции честные.
     */
    fun runAllMixed(cases: List<ClaimCase>) {
        check(Files.exists(reportPath)) { "нет $reportPath — сначала базовый прогон: ./run.sh run" }
        val rows = collect(cases)
        val sb = StringBuilder()
        sb.appendLine(MIXED_MARKER)
        sb.appendLine()
        sb.appendLine("Модели: ${modelsLine()}; дата: ${now()}.")
        sb.appendLine(
            "Идея дня 8 «своя модель на каждый этап» вживую: pro — только на этапе 1, " +
                "разбор зашумлённого текста — единственная трудная часть конвейера; " +
                "правила (этап 2) и формулировка ответа (этап 3) механические, им хватает flash. " +
                "Монолиту такой точечный апгрейд недоступен: платить за pro пришлось бы за весь промпт целиком.",
        )
        sb.append(reportBody(rows, "###"))
        val base = Files.readString(reportPath).substringBefore(MIXED_MARKER).trimEnd()
        Files.writeString(reportPath, base + "\n\n" + sb.toString())
        println("\nМикс-секция дописана в отчёт: $reportPath")
    }

    /** Прогон серии обоими вариантами со строкой прогресса на каждый кейс. */
    private fun collect(cases: List<ClaimCase>): List<CaseRow> {
        println(
            "Сравнение на ${cases.size} кейсах: A монолит (${mono.model}) vs " +
                "B конвейер (${pipeline.stage1Model} → ${pipeline.stage2Model} → ${pipeline.stage3Model})…",
        )
        return cases.map { case ->
            val a = mono.process(case.text)
            val b = pipeline.process(case.text)
            val row = CaseRow(case, a, b)
            println(progressLine(row))
            row
        }
    }

    /** Один ad-hoc текст: подробный разбор обоих вариантов + кодовая проверка (для демо). */
    fun runOne(text: String) {
        println("Претензия: «$text»")

        println("\n── Вариант B: конвейер из трёх этапов ──")
        val b = pipeline.process(text)
        when (b) {
            is PipelineResult.Done -> {
                println("Этап 1 «Нормализация» (${pipeline.stage1Model}): ${b.fields.toCompactJson()}")
                println("Этап 2 «Решение» (${pipeline.stage2Model}, вход — только компакт-JSON): ${b.stage2Raw.trim()}")
                println("Этап 3 «Результат» (${pipeline.stage3Model}): decision=${b.finalDecision}, message=«${b.message}»")
            }
            is PipelineResult.Failed -> println("Конвейер упал на «${b.stage}»: ${b.reason}")
        }
        println(
            "Метрики B: этап1 %s; этап2 %s; этап3 %s; итого %d ток. / %d мс".format(
                Locale.ROOT,
                cell(b.stage1Metrics), cell(b.stage2Metrics), cell(b.stage3Metrics),
                b.totalMetrics.totalTokens, b.totalMetrics.latencyMs,
            ),
        )

        println("\n── Вариант A: монолит одним вызовом ──")
        val a = mono.process(text)
        when (a) {
            is MonoResult.Done -> {
                println("Решение (${mono.model}): decision=${a.decision}, message=«${a.message}»")
                println("Поля, извлечённые монолитом: ${a.fields?.toCompactJson() ?: "не вернул"}")
            }
            is MonoResult.Failed -> println("Монолит упал: ${a.reason}")
        }
        println("Метрики A: ${cell(a.metrics)}")

        println("\n── Кодовая проверка правил R1–R6 (без LLM) ──")
        pipelineFields(b)?.let { fields ->
            val code = DecisionRules.decide(fields)
            val llm = (b as? PipelineResult.Done)?.stage2Decision
            val verdict = when {
                llm == null -> "этап 2 не дал решения — сравнить не с чем"
                llm == code -> "совпадает с этапом 2"
                else -> "РАСХОДИТСЯ с этапом 2 (${llm.rule} ${llm.decision})"
            }
            println("По полям этапа 1: ${code.decision} (${code.rule}) — $verdict")
        } ?: println("По полям этапа 1: этап 1 не дал полей")
        (a as? MonoResult.Done)?.let { done ->
            done.fields?.let { fields ->
                val code = DecisionRules.decide(fields)
                val verdict = if (code.decision == done.decision) "монолит следует правилам" else "монолит НАРУШИЛ правила"
                println("По полям монолита: ${code.decision} (${code.rule}) — $verdict")
            } ?: println("По полям монолита: полей нет, проверить нечем")
        }
    }

    // ── строка прогресса ────────────────────────────────────────────────────

    private fun progressLine(row: CaseRow): String {
        val aDec = decisionA(row.mono)
        val bDec = decisionB(row.pipe)
        return "[%s] A=%-13s %s B=%-13s %s (ожид. %-13s) этап2%s токены A=%d B=%d мс A=%d B=%d".format(
            Locale.ROOT,
            row.case.id,
            aDec ?: "FAILED", mark(aDec == row.case.expected),
            bDec ?: "FAILED", mark(bDec == row.case.expected),
            row.case.expected,
            stage2VsCodeCell(row.pipe),
            row.mono.metrics.totalTokens, row.pipe.totalMetrics.totalTokens,
            row.mono.metrics.latencyMs, row.pipe.totalMetrics.latencyMs,
        )
    }

    // ── отчёт ────────────────────────────────────────────────────────────────

    /** Тело отчёта (кейсы/итоги/стоимость/вывод); `h` — префикс заголовков секций. */
    private fun reportBody(rows: List<CaseRow>, h: String): String {
        val aTotal = rows.fold(CallMetrics.ZERO) { acc, r -> acc + r.mono.metrics }
        val s1Total = rows.fold(CallMetrics.ZERO) { acc, r -> acc + r.pipe.stage1Metrics }
        val s2Total = rows.fold(CallMetrics.ZERO) { acc, r -> acc + r.pipe.stage2Metrics }
        val s3Total = rows.fold(CallMetrics.ZERO) { acc, r -> acc + r.pipe.stage3Metrics }
        val bTotal = s1Total + s2Total + s3Total

        val aHits = rows.count { decisionA(it.mono) == it.case.expected }
        val bHits = rows.count { decisionB(it.pipe) == it.case.expected }

        // Сверка «LLM решает — код проверяет».
        val bChecked = rows.mapNotNull { r -> (r.pipe as? PipelineResult.Done)?.let { it.stage2Decision to DecisionRules.decide(it.fields) } }
        val bDiverged = bChecked.count { (llm, code) -> llm != code }
        val aChecked = rows.mapNotNull { r -> (r.mono as? MonoResult.Done)?.let { done -> done.fields?.let { done.decision to DecisionRules.decide(it).decision } } }
        val aDiverged = aChecked.count { (llm, code) -> llm != code }

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("$h Кейсы")
        sb.appendLine()
        sb.appendLine("| id | кейс | ожидание | A решение | B решение | A==ожид | B==ожид | B этап 2 vs код | токены A | токены B | мс A | мс B |")
        sb.appendLine("|----|------|----------|-----------|-----------|---------|---------|-----------------|----------|----------|------|------|")
        rows.forEach { r ->
            sb.appendLine(
                "| ${r.case.id} | ${r.case.brief} | ${r.case.expected} " +
                    "| ${decisionA(r.mono) ?: "FAILED"} | ${decisionB(r.pipe) ?: "FAILED"} " +
                    "| ${mark(decisionA(r.mono) == r.case.expected)} | ${mark(decisionB(r.pipe) == r.case.expected)} " +
                    "| ${stage2VsCodeCell(r.pipe)} " +
                    "| ${r.mono.metrics.totalTokens} | ${r.pipe.totalMetrics.totalTokens} " +
                    "| ${r.mono.metrics.latencyMs} | ${r.pipe.totalMetrics.latencyMs} |",
            )
        }
        sb.appendLine()
        sb.appendLine("$h Итоги")
        sb.appendLine()
        sb.appendLine("- точность против разметки: A — $aHits/${rows.size}, B — $bHits/${rows.size}")
        sb.appendLine(
            "- B: решение этапа 2 разошлось с кодовой проверкой в $bDiverged из ${bChecked.size} кейсов " +
                "(сравниваются и решение, и id правила)",
        )
        sb.appendLine(
            "- A: финальное решение монолита разошлось с правилами по его же полям в $aDiverged из ${aChecked.size} кейсов",
        )
        val aFailed = rows.count { it.mono is MonoResult.Failed }
        val bFailed = rows.count { it.pipe is PipelineResult.Failed }
        if (aFailed > 0 || bFailed > 0) {
            sb.appendLine("- отказы: A — $aFailed, B — $bFailed")
            rows.forEach { r ->
                (r.mono as? MonoResult.Failed)?.let { sb.appendLine("  - ${r.case.id} A: ${it.reason}") }
                (r.pipe as? PipelineResult.Failed)?.let { sb.appendLine("  - ${r.case.id} B (${it.stage}): ${it.reason}") }
            }
        }
        sb.appendLine()
        sb.appendLine("$h Стоимость")
        sb.appendLine()
        sb.appendLine("| вариант | вызовы | токены (prompt+completion) | латентность, мс |")
        sb.appendLine("|---------|--------|----------------------------|-----------------|")
        sb.appendLine("| A монолит | ${aTotal.llmCalls} | ${tokens(aTotal)} | ${aTotal.latencyMs} |")
        sb.appendLine("| B этап 1 «Нормализация» | ${s1Total.llmCalls} | ${tokens(s1Total)} | ${s1Total.latencyMs} |")
        sb.appendLine("| B этап 2 «Решение» | ${s2Total.llmCalls} | ${tokens(s2Total)} | ${s2Total.latencyMs} |")
        sb.appendLine("| B этап 3 «Результат» | ${s3Total.llmCalls} | ${tokens(s3Total)} | ${s3Total.latencyMs} |")
        sb.appendLine("| B итого | ${bTotal.llmCalls} | ${tokens(bTotal)} | ${bTotal.latencyMs} |")
        sb.appendLine()
        sb.appendLine("$h Вывод")
        sb.appendLine()
        if (aTotal.totalTokens > 0 && aTotal.latencyMs > 0) {
            sb.appendLine(
                "- по токенам B/A = %.2f×, по латентности B/A = %.2f× (три последовательных вызова против одного)"
                    .format(Locale.ROOT, bTotal.totalTokens.toDouble() / aTotal.totalTokens, bTotal.latencyMs.toDouble() / aTotal.latencyMs),
            )
        }
        sb.appendLine(
            "- что даёт B за эту цену: точность $bHits/${rows.size} против $aHits/${rows.size} у A; " +
                "каждый этап — короткий промпт со строгим компактным форматом; граница между этапами " +
                "проверяема — решение этапа 2 сверяется кодом (расхождений: $bDiverged), у монолита " +
                "промежуточных шагов не видно, проверить можно только пост-фактум по его же полям (расхождений: $aDiverged)",
        )
        return sb.toString()
    }

    // ── ячейки и помощники ──────────────────────────────────────────────────

    private fun modelsLine(): String =
        "A = `${mono.model}`; B = этап 1 `${pipeline.stage1Model}`, " +
            "этап 2 `${pipeline.stage2Model}`, этап 3 `${pipeline.stage3Model}`"

    private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun decisionA(a: MonoResult): Decision? = (a as? MonoResult.Done)?.decision

    private fun decisionB(b: PipelineResult): Decision? = (b as? PipelineResult.Done)?.finalDecision

    private fun pipelineFields(b: PipelineResult): ClaimFields? = when (b) {
        is PipelineResult.Done -> b.fields
        is PipelineResult.Failed -> b.fields
    }

    /** Сверка решения этапа 2 с DecisionRules по полям этапа 1: «=R1» либо расхождение. */
    private fun stage2VsCodeCell(b: PipelineResult): String {
        val done = b as? PipelineResult.Done ?: return "—"
        val code = DecisionRules.decide(done.fields)
        return if (done.stage2Decision == code) "=${code.rule}"
        else "≠ LLM ${done.stage2Decision.rule}/${done.stage2Decision.decision} vs код ${code.rule}/${code.decision}"
    }

    private fun mark(hit: Boolean): String = if (hit) "✓" else "✗"

    private fun tokens(m: CallMetrics): String = "${m.totalTokens} (${m.promptTokens}+${m.completionTokens})"

    private fun cell(m: CallMetrics): String = "${m.totalTokens} ток./${m.latencyMs} мс"

    companion object {
        /** Заголовок микс-секции; по нему же повторный `run mixed` вырезает прежнюю. */
        const val MIXED_MARKER = "## Микс моделей: pro на трудном этапе, flash на механических"
    }
}

/** Кейс + результаты обоих вариантов — строка сравнения. */
private data class CaseRow(val case: ClaimCase, val mono: MonoResult, val pipe: PipelineResult)
