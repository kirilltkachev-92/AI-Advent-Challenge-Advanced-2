import kotlin.system.exitProcess

/**
 * Точка входа — только wiring: Config → micro + LLM → конвейер → раннер.
 * Команды: `run` (30 кейсов, конвейер + all-LLM-базлайн + output/report.md) |
 * `one <текст>` (подробный разбор одного запроса) |
 * `micro` (вся серия только уровнем 1, офлайн, 0 LLM-вызовов).
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()

    when (val mode = args.firstOrNull() ?: "run") {
        "run" -> SeriesRun().runAll(QueryCases.all)
        "one" -> {
            // gradle --args режет кавычки, поэтому текст собираем из остатка аргументов.
            val text = args.drop(1).joinToString(" ").trim()
            if (text.isEmpty()) {
                System.err.println("Использование: ./run.sh one \"<текст запроса>\"")
                exitProcess(2)
            }
            SeriesRun().runOne(text)
        }
        // Без сети: LLM-зависимости в SeriesRun ленивые, ключ не нужен.
        "micro" -> SeriesRun().runMicroOnly(QueryCases.all)
        else -> {
            System.err.println("Неизвестная команда «$mode». Доступно: run | one \"<текст>\" | micro")
            exitProcess(2)
        }
    }
}
