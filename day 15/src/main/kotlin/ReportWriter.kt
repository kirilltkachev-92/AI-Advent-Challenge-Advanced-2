import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Сравнительный отчёт прогона → output/report.md (единственный файл в output/,
 * который коммитится — запись результатов прогона). Три колонки истины по каждой
 * задаче: что поймал security step, что поймал шлюз (маскирования из аудита),
 * и «мимо обоих» — эвристический скан финального кода + структурные дыры,
 * которые не видит ни один слой. Итоги: итерации, уловы по слоям, стоимость.
 */
class ReportWriter(private val path: Path = Path.of("output/report.md")) {

    /** Эвристика «мимо обоих»: проверка + ключевые слова, по которым ищем её у ревьюера. */
    private data class BypassCheck(val issue: String, val pattern: Regex, val reviewKeywords: List<String>)

    private val bypassChecks = listOf(
        BypassCheck(
            "plaintext http:// в финальном коде",
            Regex("""["']http://"""),
            listOf("http", "https", "plaintext"),
        ),
        BypassCheck(
            "PII/секрет уходит в println/лог",
            Regex("""println\([^)]*(token|password|secret|email|phone|card|user)""", RegexOption.IGNORE_CASE),
            listOf("лог", "println", "pii"),
        ),
        BypassCheck(
            "токен/данные пишутся на диск в открытом виде",
            Regex("""(writeText|appendText|Files\.write|FileWriter|FileOutputStream)"""),
            listOf("диск", "файл", "открытом виде", "шифрован", "plaintext"),
        ),
        BypassCheck(
            "пустой/глотающий catch (Exception)",
            Regex("""catch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)\s*\{\s*\}"""),
            listOf("catch", "исключен", "exception"),
        ),
        BypassCheck(
            "слабая криптография (MD5/SHA-1/DES/ECB)",
            Regex("""MD5|SHA-1|"DES|/ECB"""),
            listOf("md5", "sha-1", "des", "ecb", "крипто"),
        ),
    )

    fun write(
        records: List<ExecutionLoop.TaskRunRecord>,
        costTotals: CostTracker.Totals,
        auditEntries: List<AuditEntry>,
    ) {
        val md = buildString {
            appendLine("# День 15 — отчёт прогона execution loop (selfrun)")
            appendLine()
            appendLine("Прогон: ${OffsetDateTime.now()} · модель ${Config.deepSeekModel()} · " +
                "внутренний шлюз 127.0.0.1:${Config.gatewayPort()} (mask mode)")
            appendLine()
            totals(records, costTotals, auditEntries)
            records.forEach { taskSection(it) }
            structuralGaps()
        }
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, md)
        println()
        println("Отчёт записан: $path")
    }

    // ── Итоги ────────────────────────────────────────────────────────────

    private fun StringBuilder.totals(
        records: List<ExecutionLoop.TaskRunRecord>,
        cost: CostTracker.Totals,
        audit: List<AuditEntry>,
    ) {
        val iterations = records.sumOf { it.iterations.size }
        val securityFindings = records.flatMap { rec ->
            rec.iterations.mapNotNull { it.review as? SecurityReviewer.ReviewResult.Parsed }
                .flatMap { it.findings }
        }
        val gatewayInputMasks = audit.flatMap { it.inputFindings }
        val gatewayOutputMasks = audit.flatMap { it.outputFindings }
        appendLine("## Итоги")
        appendLine()
        appendLine("| Метрика | Значение |")
        appendLine("|---|---|")
        appendLine("| Задач / итераций всего | ${records.size} / $iterations |")
        appendLine("| Terminal-статусы | ${records.joinToString("; ") { "${it.task.id}: ${it.outcome.label}" }} |")
        appendLine("| Security step: находок всего | ${securityFindings.size} " +
            "(${severityCounts(securityFindings)}) |")
        appendLine("| Security step: возвратов на доработку | " +
            "${records.sumOf { r -> r.iterations.count { it.action == "retry_security" } }} |")
        appendLine("| Шлюз: вызовов LLM через /v1/chat | ${audit.size} |")
        appendLine("| Шлюз: замаскировано на входе | ${gatewayInputMasks.size} (${typeCounts(gatewayInputMasks)}) |")
        appendLine("| Шлюз: замаскировано на выходе | ${gatewayOutputMasks.size} (${typeCounts(gatewayOutputMasks)}) |")
        appendLine("| Стоимость (cost tracker) | ${"%.6f".format(cost.costUsd)} USD, " +
            "${cost.promptTokens}+${cost.completionTokens} токенов, ${cost.requests} запросов |")
        appendLine()
    }

    // ── Секция задачи ────────────────────────────────────────────────────

    private fun StringBuilder.taskSection(rec: ExecutionLoop.TaskRunRecord) {
        appendLine("## ${rec.task.id} — «${rec.task.title}» → ${rec.outcome.label}")
        appendLine()
        appendLine("| Итерация | Шлюз: вход | Шлюз: выход | Tests gate | Security step | Действие |")
        appendLine("|---|---|---|---|---|---|")
        rec.iterations.forEach { it ->
            val gate = when (it.gateResult) {
                is GateResult.Passed -> "PASS"
                is GateResult.Failed -> "FAIL: ${clip(it.gateResult.feedback.lineSequence().first())}"
                null -> "—"
            }
            val security = when (val r = it.review) {
                is SecurityReviewer.ReviewResult.Parsed ->
                    if (r.findings.isEmpty()) "чисто"
                    else r.findings.joinToString("<br>") { f -> "[${f.severity}] ${clip(f.issue)}" }
                is SecurityReviewer.ReviewResult.Unparseable -> "ответ не распарсился (WARNING)"
                null -> "—"
            }
            appendLine("| ${it.index} | ${it.generation.inputSummary()} | ${it.generation.outputSummary()} " +
                "| $gate | $security | ${it.action} |")
        }
        appendLine()
        bypassSection(rec)
    }

    /** «Мимо обоих»: эвристики по финальному закоммиченному коду. */
    private fun StringBuilder.bypassSection(rec: ExecutionLoop.TaskRunRecord) {
        val code = rec.finalCode ?: run {
            appendLine("Мимо обоих: коммита нет — финальный код не анализировался.")
            appendLine()
            return
        }
        val reviewIssues = rec.iterations
            .mapNotNull { it.review as? SecurityReviewer.ReviewResult.Parsed }
            .flatMap { it.findings }.joinToString(" ") { it.issue.lowercase() }
        val missed = bypassChecks.filter { check ->
            check.pattern.containsMatchIn(code) && check.reviewKeywords.none { it in reviewIssues }
        }
        appendLine(
            if (missed.isEmpty()) "Мимо обоих (эвристики по финальному коду): не найдено."
            else "Мимо обоих (эвристики по финальному коду): " + missed.joinToString("; ") { it.issue } + ".",
        )
        appendLine()
    }

    // ── Структурные дыры ─────────────────────────────────────────────────

    private fun StringBuilder.structuralGaps() {
        appendLine("## Структурно мимо обоих слоёв")
        appendLine()
        appendLine("- Шлюз видит только промпты и ответы LLM: запись файлов в workspace и `git commit` " +
            "идут мимо него — код с проблемой, пропущенной ревьюером, попадает в git без второй проверки.")
        appendLine("- `DB_PASSWORD` в AppConfig не имеет известного префикса (sk-/ghp_/AKIA) — " +
            "SecretScanner шлюза его не маскирует; поймать может только security step, и то если догадается.")
        appendLine("- OutputGuard проверяет утечку только дефолтного системного промпта шлюза, " +
            "а не кастомных промптов генерации/review, переданных полем `system`.")
        appendLine("- Security review видит код уже ПОСЛЕ маскирования шлюза: плейсхолдер [REDACTED_*] " +
            "сообщает о секрете, но точное значение и его валидность ревьюеру недоступны.")
        appendLine()
    }

    // ── Помощники ────────────────────────────────────────────────────────

    private fun severityCounts(findings: List<ReviewFinding>): String =
        findings.groupingBy { it.severity }.eachCount().entries
            .sortedBy { listOf("critical", "high", "medium", "low").indexOf(it.key) }
            .joinToString(", ") { "${it.key}: ${it.value}" }
            .ifEmpty { "—" }

    private fun typeCounts(findings: List<Finding>): String =
        findings.groupingBy { it.type.name }.eachCount().entries
            .joinToString(", ") { "${it.key}: ${it.value}" }
            .ifEmpty { "—" }

    private fun clip(text: String, max: Int = 90): String =
        if (text.length <= max) text else text.take(max) + "…"
}
