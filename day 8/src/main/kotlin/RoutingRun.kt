import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Прогон серии через ModelRouter и отчёт output/report.md:
 * по-запросная таблица (ярус, категория, confidence, сработавшая эвристика,
 * токены, латентность) + итоги: сколько осталось на flash, сколько ушло на pro
 * и по каким эвристикам, стоимость по ярусам и экономия против стратегии
 * «всё сразу на pro». Экономия считается в токенах, не в валюте: гипотетический
 * all-pro экстраполируется из фактического среднего размера pro-вызова.
 */
class RoutingRun(
    private val router: ModelRouter,
    private val reportPath: Path = Path.of("output", "report.md"),
) {
    /** Вся серия: строка прогресса на каждый запрос + отчёт в markdown. */
    fun runAll(tickets: List<Ticket>) {
        println("Роутинг ${tickets.size} обращений: ${Config.cheapModel()} → ${Config.strongModel()}…")
        val rows = tickets.map { ticket ->
            val result = router.route(ticket.text)
            val line = "[%s] %-5s → %-19s conf=%s эвристика=%-16s токенов=%d %d мс".format(
                Locale.ROOT,
                ticket.id, tierCell(result), categoryCell(result), confidenceCell(result),
                result.heuristicOrDash(), result.totalMetrics.totalTokens, result.totalMetrics.latencyMs,
            )
            println(line)
            ticket to result
        }
        writeReport(rows)
        println("\nОтчёт записан: $reportPath")
    }

    /** Один ad-hoc запрос: подробный маршрут в stdout (для демо). */
    fun runOne(text: String) {
        println("Обращение: «$text»")
        println("Маршрутизирую: сначала ${Config.cheapModel()}, эскалация — ${Config.strongModel()}…")
        val result = router.route(text)
        when (result) {
            is RouteResult.Routed -> {
                println("Ярус ответа: ${result.tier.label} (${modelOf(result.tier)})")
                if (result.escalated) {
                    println("Эскалация: да, эвристика «${result.firedHeuristic}»")
                    result.cheapAttempt?.let {
                        println("Попытка flash: ${it.category} conf=%.2f — ${it.reason}".format(Locale.ROOT, it.confidence))
                    } ?: println("Попытка flash: валидного ответа нет (${result.firedHeuristic})")
                } else {
                    println("Эскалация: нет — эвристики молчат, ответ flash принят")
                }
                val a = result.answer
                println("Ответ: ${a.category} conf=%.2f — ${a.reason}".format(Locale.ROOT, a.confidence))
            }
            is RouteResult.Failed -> {
                println("Ярус ответа: нет — оба яруса не дали валидного ответа")
                println("Эвристика эскалации: «${result.firedHeuristic}»")
                println("Причина: ${result.reason}")
            }
        }
        println(
            "Метрики: flash %d вызовов/%d ток./%d мс, pro %d вызовов/%d ток./%d мс".format(
                Locale.ROOT,
                result.cheapMetrics.llmCalls, result.cheapMetrics.totalTokens, result.cheapMetrics.latencyMs,
                result.strongMetrics.llmCalls, result.strongMetrics.totalTokens, result.strongMetrics.latencyMs,
            ),
        )
    }

    // ── отчёт ────────────────────────────────────────────────────────────────

    private fun writeReport(rows: List<Pair<Ticket, RouteResult>>) {
        val results = rows.map { it.second }
        val cheapTotal = results.fold(CallMetrics.ZERO) { acc, r -> acc + r.cheapMetrics }
        val strongTotal = results.fold(CallMetrics.ZERO) { acc, r -> acc + r.strongMetrics }
        val stayed = results.count { it is RouteResult.Routed && !it.escalated }
        val escalated = results.count { it is RouteResult.Routed && it.escalated }
        val failed = results.count { it is RouteResult.Failed }
        val heuristicBreakdown = results.mapNotNull { r ->
            when (r) {
                is RouteResult.Routed -> r.firedHeuristic
                is RouteResult.Failed -> r.firedHeuristic
            }
        }.groupingBy { it }.eachCount()

        val sb = StringBuilder()
        sb.appendLine("# День 8 — отчёт роутинга между моделями")
        sb.appendLine()
        sb.appendLine(
            "Ярусы: flash = `${Config.cheapModel()}`, pro = `${Config.strongModel()}`; " +
                "дата: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.",
        )
        sb.appendLine(
            "Стратегия: каждый запрос сначала на flash; эскалация на pro по любой из эвристик " +
                "(${ModelRouter.HEURISTIC_LOW_CONFIDENCE} | ${ModelRouter.HEURISTIC_INVALID} | " +
                "${ModelRouter.HEURISTIC_CATCH_ALL} | ${ModelRouter.HEURISTIC_TRANSPORT}). " +
                "Ответ pro принимается без дальнейших порогов — третьего яруса нет.",
        )
        sb.appendLine()
        sb.appendLine("## Запросы")
        sb.appendLine()
        sb.appendLine("| id | запрос | ярус | категория | conf | эвристика | токены | мс | ожидание |")
        sb.appendLine("|----|--------|------|-----------|------|-----------|--------|----|----------|")
        rows.forEach { (ticket, r) ->
            sb.appendLine(
                "| ${ticket.id} | ${shorten(ticket.text)} | ${tierCell(r)} | ${categoryCell(r)} | ${confidenceCell(r)} " +
                    "| ${r.heuristicOrDash()} | ${r.totalMetrics.totalTokens} | ${r.totalMetrics.latencyMs} | ${ticket.expected} |",
            )
        }
        sb.appendLine()
        sb.appendLine("## Итоги")
        sb.appendLine()
        sb.appendLine("- всего обращений: ${results.size}")
        sb.appendLine("- осталось на дешёвой модели (flash): $stayed")
        sb.appendLine("- эскалировало на сильную (pro): $escalated")
        if (failed > 0) sb.appendLine("- провалилось на обоих ярусах: $failed")
        sb.appendLine("- срабатывания эвристик:")
        if (heuristicBreakdown.isEmpty()) {
            sb.appendLine("  - (эскалаций не было)")
        } else {
            heuristicBreakdown.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                sb.appendLine("  - «$name»: $count")
            }
        }
        sb.appendLine()
        sb.appendLine("## Стоимость по ярусам")
        sb.appendLine()
        sb.appendLine("| ярус | вызовы | токены (prompt+completion) | латентность, мс |")
        sb.appendLine("|------|--------|----------------------------|-----------------|")
        sb.appendLine("| flash | ${cheapTotal.llmCalls} | ${cheapTotal.totalTokens} (${cheapTotal.promptTokens}+${cheapTotal.completionTokens}) | ${cheapTotal.latencyMs} |")
        sb.appendLine("| pro | ${strongTotal.llmCalls} | ${strongTotal.totalTokens} (${strongTotal.promptTokens}+${strongTotal.completionTokens}) | ${strongTotal.latencyMs} |")
        sb.appendLine("| итого | ${(cheapTotal + strongTotal).llmCalls} | ${(cheapTotal + strongTotal).totalTokens} | ${(cheapTotal + strongTotal).latencyMs} |")
        sb.appendLine()
        sb.appendLine("## Экономия против «всё сразу на pro»")
        sb.appendLine()
        val cheapShare = if (results.isEmpty()) 0 else stayed * 100 / results.size
        sb.appendLine("- доля запросов, обслуженных дёшево (только flash): $stayed из ${results.size} ($cheapShare%)")
        if (strongTotal.llmCalls > 0) {
            val avgProTokens = strongTotal.totalTokens.toDouble() / strongTotal.llmCalls
            val hypotheticalAllPro = avgProTokens * results.size
            val actualProTokens = strongTotal.totalTokens
            sb.appendLine(
                "- фактические pro-токены: $actualProTokens (${strongTotal.llmCalls} вызовов); " +
                    "гипотетический all-pro: ~${hypotheticalAllPro.toInt()} токенов " +
                    "(средний pro-вызов %.0f ток. × ${results.size} запросов)".format(Locale.ROOT, avgProTokens),
            )
            sb.appendLine(
                ("- роутинг тратит на pro %.2f× от all-pro; сверху — дешёвые flash-токены: ${cheapTotal.totalTokens} " +
                    "(они на порядок дешевле pro-токенов, в валюту не переводим)")
                    .format(Locale.ROOT, actualProTokens / hypotheticalAllPro),
            )
        } else {
            sb.appendLine("- эскалаций не было: pro-токенов 0, вся серия закрыта flash")
        }

        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, sb.toString())
    }

    // ── форматирование ячеек ────────────────────────────────────────────────

    private fun tierCell(r: RouteResult): String = when (r) {
        is RouteResult.Routed -> r.tier.label
        is RouteResult.Failed -> "—"
    }

    private fun categoryCell(r: RouteResult): String = when (r) {
        is RouteResult.Routed -> r.answer.category
        is RouteResult.Failed -> "FAILED"
    }

    private fun confidenceCell(r: RouteResult): String = when (r) {
        is RouteResult.Routed -> "%.2f".format(Locale.ROOT, r.answer.confidence)
        is RouteResult.Failed -> "—"
    }

    private fun RouteResult.heuristicOrDash(): String = when (this) {
        is RouteResult.Routed -> firedHeuristic ?: "—"
        is RouteResult.Failed -> firedHeuristic ?: "—"
    }

    private fun shorten(text: String, max: Int = 60): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun modelOf(tier: Tier): String = when (tier) {
        Tier.CHEAP -> Config.cheapModel()
        Tier.STRONG -> Config.strongModel()
    }
}
