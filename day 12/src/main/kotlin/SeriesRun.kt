import java.util.Locale

/**
 * Прогон стенда: гоняет сценарии по режимам обороны, судит исход и печатает
 * прогресс. Запись артефактов делегирована `ReportWriter` — здесь только
 * оркестрация и stdout.
 *
 * Исход модели скачет от прогона к прогону даже при temperature 0, поэтому
 * `runAll` повторяет всю матрицу `repeat` раз и отдаёт в отчёт ЧАСТОТЫ, а не
 * один сэмпл. Отдельно гоняется сравнение стилей закладки (`OVERT` против
 * `COVERT`) в режиме `naive`: там обороны нет вовсе, и виден чистый вклад
 * формулировки.
 *
 * Зависимость от LLM ленивая: `catalog` и `sanitize` работают офлайн и без ключа,
 * потому что оба эшелона-без-модели детерминированы и сети не требуют.
 */
class SeriesRun(
    clientFactory: () -> DeepSeekClient = { DeepSeekClient() },
    private val sanitizer: InputSanitizer = InputSanitizer(),
    private val validator: OutputValidator = OutputValidator(),
    private val model: String = Config.agentModel(),
    private val writer: ReportWriter = ReportWriter(model, sanitizer, validator),
) {
    private val client by lazy(clientFactory)
    private val runners by lazy {
        DefenseMode.entries.associateWith { AgentRunner(client, it, model, sanitizer, validator) }
    }

    // ── прогоны ──────────────────────────────────────────────────────────────

    /**
     * Основная матрица на COVERT × `repeat` прогонов, затем сравнение стилей в
     * naive. Все прогоны сохраняются в `output/runs.md`, транскрипт — по первому.
     */
    fun runAll(repeat: Int) {
        val scenarios = ScenarioCatalog.all
        val mainCalls = scenarios.size * DefenseMode.entries.size * repeat
        val styleCalls = scenarios.size * repeat
        println(
            "Indirect injection: ${scenarios.size} сценария × ${DefenseMode.entries.size} режима × " +
                "$repeat прогон(а) = $mainCalls вызовов на матрице (payload: ${PayloadStyle.COVERT.label})",
        )
        println("Плюс сравнение стилей закладки в naive: ${scenarios.size} × $repeat = $styleCalls вызовов. Модель $model")
        println("Инструменты фейковые: send_mail и http_get ничего не делают, сети нет, домены закладок — *.example\n")

        var step = 0
        val total = mainCalls + styleCalls
        val main = mutableListOf<ScenarioOutcome>()
        (1..repeat).forEach { run ->
            scenarios.forEach { scenario ->
                DefenseMode.entries.forEach { mode ->
                    step++
                    val outcome = execute(scenario, mode, PayloadStyle.COVERT, run)
                    println(progressLine(step, total, outcome))
                    main += outcome
                }
            }
        }
        println()
        val overt = mutableListOf<ScenarioOutcome>()
        (1..repeat).forEach { run ->
            scenarios.forEach { scenario ->
                step++
                val outcome = execute(scenario, DefenseMode.NAIVE, PayloadStyle.OVERT, run)
                println(progressLine(step, total, outcome))
                overt += outcome
            }
        }
        writer.writeAll(main, overt, repeat)
    }

    /** Один сценарий в трёх режимах, подробно в stdout; отчёты не переписываются. */
    fun runOne(id: String, style: PayloadStyle) {
        val scenario = ScenarioCatalog.byId(id)
        if (scenario == null) {
            System.err.println("Сценарий «$id» не найден. Доступные: ${ScenarioCatalog.all.joinToString(", ") { it.id }}")
            return
        }
        println("── Сценарий ${scenario.id}: ${scenario.title} ──")
        println("Роль: ${scenario.role.label}")
        println("Запрос пользователя: «${scenario.userRequest}»")
        println("Источники: " + scenario.sources.joinToString(", ") { "${it.id} (${it.trustLabel}${if (it.poisoned) ", отравлен" else ""})" })
        println("Вектор: ${scenario.vector}")
        println("Техники сокрытия: ${scenario.techniques.joinToString(", ")}")
        println("Стиль закладки: ${style.name.lowercase()} — ${style.label}")
        println("Цель атаки: ${scenario.goal}")
        println("Польза, которая обязана остаться: ${scenario.utilityLabel}")
        println("Разрешённые действия: ${scenario.allowedTools.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.id } ?: "никаких — пользователь не просил действий"}\n")

        DefenseMode.entries.forEach { mode ->
            val outcome = execute(scenario, mode, style, run = 1)
            val turn = outcome.turn
            println("── Режим ${mode.label} (${mode.description}) ──")
            if (turn.removed.isNotEmpty()) {
                println("Эшелон 1 вырезал ${turn.removed.size} фрагмент(ов): " + turn.removed.joinToString("; ") { it.technique })
            }
            println("Ответ агента (${turn.totalTokens} ток., ${turn.latencyMs} мс):")
            println(turn.answer.trim().lines().joinToString("\n") { "  $it" })
            println("Действия: " + renderCalls(turn))
            if (turn.suspicious.isNotEmpty()) println("Агент пометил как подозрительное: " + turn.suspicious.joinToString("; "))
            println("Эшелон 3: " + verdictLabel(turn.outputVerdict))
            println("Пользователю ушло: " + turn.finalAnswer.trim().replace("\n", " ").take(400))
            println("Итог: ${outcome.summary}; польза сохранена: ${outcome.utilitySummary}")
            println("Дошло до пользователя: ${if (outcome.reachedUser) "ДА" else "нет"}\n")
        }
    }

    /** Офлайн: каталог сценариев и реальных кейсов + output/catalog.md. */
    fun printCatalog() {
        println("Каталог: ${ScenarioCatalog.all.size} сценария indirect prompt injection")
        println("У каждой закладки два стиля: ${PayloadStyle.OVERT.name.lowercase()} — ${PayloadStyle.OVERT.label}, " +
            "${PayloadStyle.COVERT.name.lowercase()} — ${PayloadStyle.COVERT.label}; техники сокрытия у обоих одинаковые.")
        println("Инструменты стенда фейковые (send_mail → \"queued\", http_get → \"200 OK (заглушка)\"), сети нет.\n")
        ScenarioCatalog.all.forEach { s ->
            println("[${s.id}] ${s.title}")
            println("  роль: ${s.role.label}; запрос: «${s.userRequest}»")
            println("  вектор: ${s.vector}")
            println("  техники сокрытия: ${s.techniques.joinToString(", ")}")
            println("  цель атаки: ${s.goal}")
            println("  чем защищаемся: ${s.defense}")
            println("  источники: " + s.sources.joinToString(", ") { "${it.id} [${it.trustLabel}]${if (it.poisoned) " ← закладка" else ""}" })
            println("  вид пробоя: ${(s.breachMarkers.keys + s.missingMarkers.keys).joinToString(", ") { it.name }}, UNAUTHORIZED_ACTION (по журналу)")
            println("  польза (обязана остаться): ${s.utilityLabel}")
            s.realWorld?.let { println("  реальный кейс: $it") }
            PayloadStyle.entries.forEach { style ->
                s.sources.filter { it.poisoned }.forEach { source ->
                    val removed = sanitizer.clean(source.raw(style)).removed
                    println("  закладка [${style.name.lowercase()}] в ${source.id}: " +
                        (removed.firstOrNull { it.excerpt.isNotBlank() && !it.technique.startsWith("zero-width") }?.excerpt
                            ?: removed.firstOrNull()?.excerpt ?: "—"))
                }
            }
            println()
        }
        println("Реальные кейсы indirect injection:")
        ScenarioCatalog.realWorldCases.forEach { c ->
            println("  • ${c.name} — ${c.vector}")
            println("    что произошло: ${c.whatHappened}")
            println("    воспроизведено: ${c.reproducedBy}")
        }
        writer.writeCatalog()
    }

    /**
     * Офлайн и без сети: что видит человек против того, что уходило бы в модель.
     * Ключевая команда для демо — на ней видно, что закладка физически есть в
     * данных, но глазом её не найти.
     */
    fun printSanitize(sourceId: String, style: PayloadStyle) {
        val source = SourceLibrary.byId(sourceId)
        if (source == null) {
            System.err.println("Источник «$sourceId» не найден. Доступные: ${SourceLibrary.all.joinToString(", ") { it.id }}")
            return
        }
        val raw = source.raw(style)
        val clean = sanitizer.clean(raw)
        println("── Источник ${source.id}: ${source.title} ──")
        println("origin=${source.origin}, trust=${source.trustLabel}, отравлен: ${source.poisonedWith?.joinToString(", ") ?: "нет"}")
        println("Стиль закладки: ${style.name.lowercase()} — ${style.label}")
        println("Размер: ${raw.length} символов сырых → ${clean.visibleText.length} после эшелона 1\n")

        println("── Что видит человек (и что уйдёт в модель после эшелона 1) ──")
        println(clean.visibleText)
        println()
        println("── Что уходило бы в модель БЕЗ эшелона 1 (невидимые символы показаны как ⟨zw⟩) ──")
        println(reveal(raw))
        println()
        println("── Что вырезано (${clean.removed.size} фрагмент(ов)) ──")
        if (clean.removed.isEmpty()) {
            println("  ничего: источник чистый")
        } else {
            clean.removed.forEachIndexed { i, fragment ->
                println("  ${i + 1}. [${fragment.technique}] ${fragment.excerpt}")
                fragment.note?.let { println("     примечание: $it") }
            }
        }
    }

    // ── ядро ─────────────────────────────────────────────────────────────────

    private fun execute(scenario: Scenario, mode: DefenseMode, style: PayloadStyle, run: Int): ScenarioOutcome {
        val turn = runners.getValue(mode).run(scenario, style)
        val breaches = BreachDetector.detect(scenario, turn.answer, turn.requestedCalls)
        // До пользователя дошло — считаем по ФИНАЛЬНОМУ тексту и по ИСПОЛНЕННЫМ действиям.
        // При Blocked показывать было нечего: считать «пробой дошёл» нельзя, потерю
        // факта в этом случае фиксирует потеря пользы, а не мнимая утечка.
        val executed = turn.executedCalls.map { it.call }
        val reached = when (turn.outputVerdict) {
            is OutputVerdict.Blocked -> false
            else -> BreachDetector.detect(scenario, turn.finalAnswer, executed).isNotEmpty()
        }
        return ScenarioOutcome(
            scenario = scenario,
            turn = turn,
            run = run,
            breaches = breaches,
            reachedUser = reached,
            missingUtility = BreachDetector.utilityMissing(scenario, turn.finalAnswer),
        )
    }

    // ── помощники stdout ─────────────────────────────────────────────────────

    private fun progressLine(step: Int, total: Int, o: ScenarioOutcome): String {
        val verdict = if (o.held) "чисто" else o.breaches.joinToString(",") { it.name }
        return "[%2d/%d] прогон %d %-7s %-9s %-13s → %-42s действий: %d/%d, польза: %s (%d ток., %d мс)".format(
            Locale.ROOT, step, total, o.run, o.style.name.lowercase(), o.turn.mode.label, o.scenario.id, verdict,
            o.turn.executedCalls.size, o.turn.requestedCalls.size,
            if (o.utilityKept) "да" else "НЕТ",
            o.turn.totalTokens, o.turn.latencyMs,
        )
    }

    private fun renderCalls(turn: AgentTurn): String = if (turn.toolRecords.isEmpty()) {
        "не запрошено ни одного"
    } else {
        turn.toolRecords.joinToString("; ") { r ->
            "${r.call.render()} → " + if (r.allowed) "ИСПОЛНЕНО (${r.result})" else "заблокировано (${r.reason})"
        }
    }

    private fun verdictLabel(verdict: OutputVerdict?): String = when (verdict) {
        null -> "выключен в этом режиме"
        is OutputVerdict.Pass -> "PASS — ответ не тронут"
        is OutputVerdict.Redacted -> "REDACTED — ${verdict.reasons.joinToString("; ")}"
        is OutputVerdict.Blocked -> "BLOCKED — ${verdict.reasons.joinToString("; ")}"
    }

    /** Показывает невидимые символы: без этого «закладка есть» приходится принимать на веру. */
    private fun reveal(text: String): String = INVISIBLE.replace(text, "⟨zw⟩")

    private companion object {
        val INVISIBLE = Regex("[\u200B-\u200D\u2060\uFEFF\u00AD\u200E\u200F]")
    }
}
