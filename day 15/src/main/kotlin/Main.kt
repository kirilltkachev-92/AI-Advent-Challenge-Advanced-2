import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Точка входа — только wiring. День 15 — боевой red-team стенд.
 * Режимы CLI:
 *   serve            — поднять внутренний LLM Gateway (127.0.0.1) + публичный
 *                      AgentApi (bindHost:port) и держать до Ctrl+C — режим боя/деплоя;
 *   attack [url]     — прогнать red-team-набор по цели (дефолт — свой localhost) →
 *                      output/attack-report.md + машинный output/runs-<phase>.json;
 *   selfrun [id]     — батч 3 провокационных задач (доказать, что пайплайн жив) →
 *                      output/report.md;
 *   report           — напечатать последний selfrun-отчёт.
 * Без аргументов — serve.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (args.firstOrNull()?.takeIf { it.isNotBlank() }) {
        null, "serve" -> serve()
        "attack" -> attack(args.getOrNull(1), args.getOrNull(2))
        "selfrun" -> {
            val id = args.getOrNull(1)
            val tasks = if (id.isNullOrBlank()) TaskCatalog.ALL else listOfNotNull(TaskCatalog.byId(id))
            if (tasks.isEmpty()) {
                System.err.println("Неизвестная задача «$id». Доступны: ${TaskCatalog.ALL.joinToString { it.id }}")
                exitProcess(2)
            }
            selfrun(tasks)
        }
        "report" -> printReport()
        else -> {
            System.err.println("Использование: ./run.sh [serve] | attack [url] [phase] | selfrun [id] | report")
            exitProcess(2)
        }
    }
}

/** Собирает внутренний шлюз (loopback) и execution loop поверх свежего workspace. */
private fun buildStack(apiKey: String): Triple<Workspace, ExecutionLoop, AuditLog> {
    val scanner = SecretScanner()
    val audit = AuditLog()
    val costs = CostTracker()
    HttpApi(
        inputGuard = InputGuard(scanner),
        outputGuard = OutputGuard(scanner),
        client = DeepSeekClient(apiKey),
        audit = audit,
        costs = costs,
    ).start()
    println("Внутренний LLM Gateway: http://127.0.0.1:${Config.gatewayPort()} (mask mode, loopback only)")

    val workspace = Workspace()
    workspace.reset()
    val llm = LoopLlmClient()
    val loop = ExecutionLoop(
        llm = llm,
        workspace = workspace,
        gate = TestsGate(workspace.root),
        reviewer = SecurityReviewer(llm),
    )
    return Triple(workspace, loop, audit)
}

/** Боевой режим: публичный AgentApi + внутренний шлюз, держим до Ctrl+C. */
private fun serve() {
    val apiKey = requireKey()
    val (workspace, loop, _) = buildStack(apiKey)
    AgentApi(loop, workspace).start()
    val auth = if (Config.agentToken().isNullOrBlank()) "открытый (без токена)" else "по Bearer-токену"
    println("Публичный AgentApi: http://${Config.bindHost()}:${Config.port()}  (доступ: $auth)")
    println("  POST /v1/execute {\"prompt\": \"...\", \"files\": [{\"name\",\"content\"}]?}")
    println("  GET  /healthz")
    println("Стенд поднят. Ctrl+C для остановки. (охраняемый секрет — в системном промпте агента)")
    // Серверы держат non-daemon пул потоков — JVM живёт после возврата main.
}

/** Red-team-прогон по цели. */
private fun attack(url: String?, phase: String?) {
    val target = url?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:${Config.port()}"
    RedTeamRunner(target, Config.agentToken()).run(phase?.takeIf { it.isNotBlank() } ?: "before")
    exitProcess(0)
}

/** Батч провокационных задач — доказательство, что пайплайн жив. */
private fun selfrun(tasks: List<ProvocationTask>) {
    val apiKey = requireKey()
    val scanner = SecretScanner()
    val audit = AuditLog()
    val costs = CostTracker()
    val server = HttpApi(InputGuard(scanner), OutputGuard(scanner), DeepSeekClient(apiKey), audit, costs).start()
    println("Внутренний LLM Gateway: http://127.0.0.1:${Config.gatewayPort()} (mask mode)")

    val workspace = Workspace()
    workspace.reset()
    println("Workspace: ${workspace.root} (шаблон с фейковыми секретами, git init + initial commit)")

    val llm = LoopLlmClient()
    val loop = ExecutionLoop(llm, workspace, TestsGate(workspace.root), SecurityReviewer(llm))
    val records = tasks.map { loop.run(it) }
    ReportWriter().write(records, costs.totals(), audit.tail(200))

    println()
    println("Итог: " + records.joinToString("; ") { "${it.task.id} → ${it.outcome.label}" })
    val totals = costs.totals()
    println("Стоимость сессии: %.6f USD (%d запросов через шлюз)".format(totals.costUsd, totals.requests))
    server.stop(0)
    exitProcess(0)
}

private fun printReport() {
    val report = Path.of("output/report.md")
    if (!report.exists()) {
        System.err.println("Отчёта ещё нет — сначала прогони ./run.sh selfrun")
        exitProcess(2)
    }
    println(report.readText())
}

private fun requireKey(): String = Config.deepSeekApiKey() ?: run {
    System.err.println("DEEPSEEK_API_KEY не задан — заполни .env (см. .env.example)")
    exitProcess(2)
}
