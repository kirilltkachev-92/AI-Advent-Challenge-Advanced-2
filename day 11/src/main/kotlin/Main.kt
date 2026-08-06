import kotlin.system.exitProcess

/**
 * Точка входа — только wiring: Config → RedTeamRun → команда.
 * Команды: `run` (все атаки × три режима + output/report.md и transcript.md) |
 * `one <id>` (одна атака подробно во всех трёх режимах) |
 * `catalog` (коллекция атак с классификацией, офлайн) |
 * `guard "<текст>"` (детерминированные эшелоны на произвольном тексте, офлайн).
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()

    when (val mode = args.firstOrNull() ?: "run") {
        "run" -> RedTeamRun().runAll()
        "one" -> {
            // gradle --args режет кавычки, поэтому остаток аргументов собираем обратно.
            val id = args.drop(1).joinToString(" ").trim()
            if (id.isEmpty()) {
                System.err.println("Использование: ./run.sh one <id атаки>")
                exitProcess(2)
            }
            RedTeamRun().runOne(id)
        }
        // Ниже — офлайн: LLM-зависимости в RedTeamRun ленивые, ключ не нужен.
        "catalog" -> RedTeamRun().printCatalog()
        "guard" -> {
            val text = args.drop(1).joinToString(" ").trim()
            if (text.isEmpty()) {
                System.err.println("Использование: ./run.sh guard \"<текст>\"")
                exitProcess(2)
            }
            RedTeamRun().checkGuards(text)
        }
        else -> {
            System.err.println("Неизвестная команда «$mode». Доступно: run | one <id> | catalog | guard \"<текст>\"")
            exitProcess(2)
        }
    }
}
