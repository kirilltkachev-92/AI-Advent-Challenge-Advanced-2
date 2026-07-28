import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Baseline-замер БАЗОВОЙ модели без файнтюна на первых 10 примерах eval.jsonl —
 * точка отсчёта для сравнения «до/после». Модель: gpt-4o-mini при наличии
 * OPENAI_API_KEY, иначе fallback на DeepSeek (какая использована — печатается
 * и фиксируется в отчёте). temperature 0.0 — замер должен быть воспроизводимым.
 * Пишет output/baseline.md: таблица ответов + accuracy + format-compliance +
 * критерии улучшения после файнтюна; итоги дублируются в консоль.
 */
class BaselineRunner(private val sampleSize: Int = 10) {

    private val json = Json { ignoreUnknownKeys = true }

    fun run() {
        val evalPath = Path.of("data/eval.jsonl")
        check(Files.exists(evalPath)) { "$evalPath не найден — сначала ./run.sh prepare" }
        val examples = Files.readAllLines(evalPath)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<TrainingExample>(it) }
            .take(sampleSize)
        check(examples.isNotEmpty()) { "eval.jsonl пуст" }

        // Выбор модели: OpenAI при наличии ключа, иначе DeepSeek как точка отсчёта
        val openAiKey = Config.openAiApiKey()
        val modelName: String
        val ask: (String) -> String
        if (openAiKey != null) {
            modelName = Config.openAiBaseModel()
            val client = OpenAiClient(openAiKey)
            ask = { review -> client.chat(TrainingExample.SYSTEM_PROMPT, review, temperature = 0.0) }
        } else {
            modelName = Config.deepSeekModel()
            val client = DeepSeekClient()
            ask = { review -> client.chat(TrainingExample.SYSTEM_PROMPT, review, temperature = 0.0) }
        }
        println("Baseline-модель: $modelName (OPENAI_API_KEY ${if (openAiKey != null) "задан" else "не задан → fallback DeepSeek"})")

        val rows = examples.mapIndexed { index, example ->
            val answer = ask(example.reviewText)
            val row = BaselineRow(index + 1, example.reviewText, example.label, answer)
            println("${row.number}. эталон=${row.expected}, ответ=«${row.rawAnswer.take(60)}» → ${row.verdict()}")
            row
        }
        val exact = rows.count { it.exactMatch() }
        val format = rows.count { it.formatOk() }
        println("Accuracy (exact-match): $exact/${rows.size}")
        println("Format-compliance (ровно одно слово из трёх): $format/${rows.size}")

        Files.createDirectories(Path.of("output"))
        Files.writeString(Path.of("output/baseline.md"), renderReport(modelName, rows, exact, format))
        println("Отчёт: output/baseline.md")
    }

    private fun renderReport(model: String, rows: List<BaselineRow>, exact: Int, format: Int): String = buildString {
        appendLine("# Baseline: базовая модель без файнтюна")
        appendLine()
        appendLine("- Модель: `$model`" + if (Config.openAiApiKey() == null) " (fallback: OPENAI_API_KEY не задан, gpt-4o-mini включается ключом)" else "")
        appendLine("- Данные: первые ${rows.size} примеров `data/eval.jsonl`, temperature 0.0")
        appendLine("- System-промпт тот же, что в датасете файнтюна")
        appendLine()
        appendLine("| № | Отзыв (кратко) | Эталон | Ответ модели (raw) | Вердикт |")
        appendLine("|---|---|---|---|---|")
        rows.forEach { row ->
            appendLine("| ${row.number} | ${cell(row.review, 60)} | ${row.expected} | ${cell(row.rawAnswer, 80)} | ${row.verdict()} |")
        }
        appendLine()
        appendLine("## Итого")
        appendLine()
        appendLine("- **Accuracy (exact-match): $exact/${rows.size}** — ответ побайтово равен эталонной метке")
        appendLine("- **Format-compliance: $format/${rows.size}** — ответ ровно одно слово из {позитивный, нейтральный, негативный}")
        appendLine()
        appendLine("## Критерии улучшения после файнтюна")
        appendLine()
        appendLine("1. Accuracy exact-match выше baseline ($exact/${rows.size}) на тех же примерах eval.")
        appendLine("2. Format-compliance = 100%: никаких «Тональность: позитивная.», заглавных букв и точек.")
        appendLine("3. Ответ — ровно один токен-слово, без пояснений и рассуждений (короче и дешевле inference).")
    }

    /** Ячейка markdown-таблицы: без переводов строк и «|», с ограничением длины. */
    private fun cell(text: String, limit: Int): String =
        text.replace("\n", " ").replace("|", "\\|").let { if (it.length > limit) it.take(limit) + "…" else it }
}

/** Одна строка замера: ответ модели как есть + два независимых вердикта. */
data class BaselineRow(val number: Int, val review: String, val expected: String, val rawAnswer: String) {
    /** Формат соблюдён: ровно одно слово из трёх, нижний регистр, без точки. */
    fun formatOk(): Boolean = rawAnswer.trim() in TrainingExample.VALID_LABELS
    fun exactMatch(): Boolean = rawAnswer.trim() == expected
    fun verdict(): String = when {
        exactMatch() -> "exact-match"
        formatOk() -> "формат ok, метка неверна"
        else -> "формат нарушен"
    }
}
