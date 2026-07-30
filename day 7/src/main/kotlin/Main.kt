import kotlin.system.exitProcess

/**
 * Точка входа — только wiring: Config → клиент → три подхода контроля → ворота → раннер.
 * Команды: `run` (все 12 кейсов + output/report.md) | `one <текст>` (один вердикт).
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val client = DeepSeekClient()
    val gate = InferenceGate(
        redundancy = RedundancyRunner(client),
        constraints = ConstraintChecker(),
        selfChecker = SelfChecker(client),
    )
    val runner = QualityRun(gate)

    when (val mode = args.firstOrNull() ?: "run") {
        "run" -> runner.runAll(TestCases.all)
        "one" -> {
            // gradle --args режет кавычки, поэтому текст собираем из остатка аргументов.
            val text = args.drop(1).joinToString(" ").trim()
            if (text.isEmpty()) {
                System.err.println("Использование: ./run.sh one \"<текст поручения>\"")
                exitProcess(2)
            }
            runner.runOne(text)
        }
        else -> {
            System.err.println("Неизвестная команда «$mode». Доступно: run | one \"<текст>\"")
            exitProcess(2)
        }
    }
}
