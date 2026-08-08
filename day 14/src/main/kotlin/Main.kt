import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Точка входа — только wiring. CLI: без аргументов — все 3 провокационные
 * задачи; `one <id>` — одна задача; `report` — печать последнего отчёта.
 * Перед лупом в этом же JVM поднимается встроенный LLM Gateway (порт 8014):
 * луп ходит в него по-настоящему, по HTTP.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (args.firstOrNull()?.takeIf { it.isNotBlank() }) {
        null -> runLoop(TaskCatalog.ALL)
        "one" -> {
            val id = args.getOrNull(1)
            val task = id?.let(TaskCatalog::byId)
            if (task == null) {
                System.err.println("Неизвестная задача «${id ?: ""}». Доступны: ${TaskCatalog.ALL.joinToString { it.id }}")
                exitProcess(2)
            }
            runLoop(listOf(task))
        }
        "report" -> {
            val report = Path.of("output/report.md")
            if (!report.exists()) {
                System.err.println("Отчёта ещё нет — сначала прогони ./run.sh")
                exitProcess(2)
            }
            println(report.readText())
        }
        else -> {
            System.err.println("Использование: ./run.sh | ./run.sh one <id> | ./run.sh report")
            exitProcess(2)
        }
    }
}

private fun runLoop(tasks: List<ProvocationTask>) {
    val apiKey = Config.deepSeekApiKey()
    if (apiKey == null) {
        System.err.println("DEEPSEEK_API_KEY не задан — лупу нечем генерировать код. Заполни .env (см. .env.example)")
        exitProcess(2)
    }

    // Встроенный шлюз — в этом же JVM; луп будет ходить в него по HTTP.
    val scanner = SecretScanner()
    val audit = AuditLog()
    val costs = CostTracker()
    val server = HttpApi(
        inputGuard = InputGuard(scanner),
        outputGuard = OutputGuard(scanner),
        client = DeepSeekClient(apiKey),
        audit = audit,
        costs = costs,
    ).start()
    println("Встроенный LLM Gateway: http://${Config.bindHost()}:${Config.port()} " +
        "(модель ${Config.deepSeekModel()}, лимит ${Config.rateLimitPerMin()}/мин, mask mode)")

    val workspace = Workspace()
    workspace.reset()
    println("Workspace: ${workspace.root} (шаблон с фейковыми секретами, git init + initial commit)")

    val llm = LoopLlmClient()
    val loop = ExecutionLoop(
        llm = llm,
        workspace = workspace,
        gate = TestsGate(workspace.root),
        reviewer = SecurityReviewer(llm),
    )

    val records = tasks.map(loop::run)
    ReportWriter().write(records, costs.totals(), audit.tail(200))

    println()
    println("Итог: " + records.joinToString("; ") { "${it.task.id} → ${it.outcome.label}" })
    val totals = costs.totals()
    println("Стоимость сессии: %.6f USD (%d запросов через шлюз)".format(totals.costUsd, totals.requests))

    server.stop(0)
    exitProcess(0)
}
