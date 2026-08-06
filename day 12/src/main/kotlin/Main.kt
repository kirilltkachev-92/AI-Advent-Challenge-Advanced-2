import kotlin.system.exitProcess

/**
 * Точка входа — только wiring: Config → SeriesRun → команда.
 * Команды: `run [--repeat N]` (матрица × N прогонов + сравнение стилей закладки,
 * пишет output/report.md, runs.md, transcript.md) |
 * `one <id> [overt|covert]` (один сценарий подробно во всех трёх режимах) |
 * `catalog` (сценарии, техники сокрытия и реальные кейсы, офлайн) |
 * `sanitize <sourceId> [overt|covert]` (что видит человек против того, что
 * уходит в модель, офлайн).
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()

    val rest = args.drop(1)
    when (val mode = args.firstOrNull() ?: "run") {
        "run" -> SeriesRun().runAll(repeat = repeatArg(rest))
        "one" -> {
            // gradle --args режет кавычки, поэтому остаток аргументов собираем обратно.
            val id = withoutStyle(rest).joinToString(" ").trim()
            if (id.isEmpty()) {
                System.err.println("Использование: ./run.sh one <id сценария> [overt|covert]")
                exitProcess(2)
            }
            SeriesRun().runOne(id, styleArg(rest))
        }
        // Ниже — офлайн: зависимость от LLM в SeriesRun ленивая, ключ и сеть не нужны.
        "catalog" -> SeriesRun().printCatalog()
        "sanitize" -> {
            val sourceId = withoutStyle(rest).joinToString(" ").trim()
            if (sourceId.isEmpty()) {
                System.err.println("Использование: ./run.sh sanitize <id источника> [overt|covert]")
                exitProcess(2)
            }
            SeriesRun().printSanitize(sourceId, styleArg(rest))
        }
        else -> {
            System.err.println(
                "Неизвестная команда «$mode». Доступно: run [--repeat N] | one <id> [overt|covert] | " +
                    "catalog | sanitize <sourceId> [overt|covert]",
            )
            exitProcess(2)
        }
    }
}

/** Число повторов матрицы; исход модели скачет, поэтому один прогон — не результат. */
private fun repeatArg(args: List<String>): Int {
    val idx = args.indexOf("--repeat")
    val value = if (idx >= 0) args.getOrNull(idx + 1)?.toIntOrNull() else null
    return (value ?: DEFAULT_REPEAT).coerceAtLeast(1)
}

/** Стиль закладки для команд `one` и `sanitize`; по умолчанию — covert, как в основной матрице. */
private fun styleArg(args: List<String>): PayloadStyle =
    PayloadStyle.entries.firstOrNull { style -> args.any { it.equals(style.name, ignoreCase = true) } }
        ?: PayloadStyle.COVERT

private fun withoutStyle(args: List<String>): List<String> =
    args.filterNot { arg -> PayloadStyle.entries.any { it.name.equals(arg, ignoreCase = true) } }

private const val DEFAULT_REPEAT = 3
