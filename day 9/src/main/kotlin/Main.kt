import kotlin.system.exitProcess

/**
 * Точка входа — только wiring: Config → клиент → оба варианта → раннер сравнения.
 * Команды: `run` (16 кейсов × A+B + output/report.md) |
 * `mixed` (те же кейсы, конвейер на смешанных моделях, секция в конец отчёта) |
 * `one <текст>` (оба варианта подробно).
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val client = DeepSeekClient()
    val runner = CompareRun(MonolithicRunner(client), StagePipeline(client))

    when (val mode = args.firstOrNull() ?: "run") {
        "run" -> runner.runAll(ClaimCases.all)
        "mixed" -> CompareRun(
            MonolithicRunner(client),
            StagePipeline(client, Config.mixedStage1Model(), Config.mixedStage2Model(), Config.mixedStage3Model()),
        ).runAllMixed(ClaimCases.all)
        "one" -> {
            // gradle --args режет кавычки, поэтому текст собираем из остатка аргументов.
            val text = args.drop(1).joinToString(" ").trim()
            if (text.isEmpty()) {
                System.err.println("Использование: ./run.sh one \"<текст претензии>\"")
                exitProcess(2)
            }
            runner.runOne(text)
        }
        else -> {
            System.err.println("Неизвестная команда «$mode». Доступно: run | mixed | one \"<текст>\"")
            exitProcess(2)
        }
    }
}
