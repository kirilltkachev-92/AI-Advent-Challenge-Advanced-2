import kotlin.system.exitProcess

/**
 * Точка входа — только wiring: Config → клиент → роутер → раннер.
 * Команды: `run` (14 обращений + output/report.md) | `one <текст>` (один маршрут).
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val runner = RoutingRun(ModelRouter(DeepSeekClient()))

    when (val mode = args.firstOrNull() ?: "run") {
        "run" -> runner.runAll(TestSeries.all)
        "one" -> {
            // gradle --args режет кавычки, поэтому текст собираем из остатка аргументов.
            val text = args.drop(1).joinToString(" ").trim()
            if (text.isEmpty()) {
                System.err.println("Использование: ./run.sh one \"<текст обращения>\"")
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
