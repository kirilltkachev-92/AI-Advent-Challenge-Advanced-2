import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Результат валидации JSONL-файла: либо всё чисто (со статистикой), либо список ошибок. */
sealed interface ValidationResult {
    data class Ok(val count: Int, val classCounts: Map<String, Int>) : ValidationResult
    data class Failed(val errors: List<Pair<Int, String>>) : ValidationResult
}

/**
 * Валидатор датасета в формате fine-tune OpenAI. Контракт строки:
 * валидный JSON; ровно три сообщения system → user → assistant в этом порядке;
 * все content непустые; метка assistant ∈ {позитивный, нейтральный, негативный};
 * system во всех строках равен эталонному TrainingExample.SYSTEM_PROMPT — иначе
 * train и eval могли бы разъехаться по инструкции. Валидатор только проверяет
 * и печатает отчёт — код выхода решает вызывающий (Main).
 */
class DatasetValidator {

    private val json = Json { ignoreUnknownKeys = true }

    fun validate(path: Path): ValidationResult {
        if (!Files.exists(path)) return ValidationResult.Failed(listOf(0 to "файл $path не найден"))
        val errors = mutableListOf<Pair<Int, String>>()
        val classCounts = mutableMapOf<String, Int>()
        var count = 0

        Files.readAllLines(path).forEachIndexed { index, line ->
            val lineNo = index + 1
            if (line.isBlank()) {
                errors += lineNo to "пустая строка"
                return@forEachIndexed
            }
            val example = try {
                json.decodeFromString<TrainingExample>(line)
            } catch (e: Exception) {
                errors += lineNo to "невалидный JSON: ${e.message?.take(120)}"
                return@forEachIndexed
            }
            val roles = example.messages.map { it.role }
            when {
                roles != listOf("system", "user", "assistant") ->
                    errors += lineNo to "роли $roles, ожидалось [system, user, assistant]"
                example.messages.any { it.content.isBlank() } ->
                    errors += lineNo to "пустой content у роли ${example.messages.first { it.content.isBlank() }.role}"
                example.label !in TrainingExample.VALID_LABELS ->
                    errors += lineNo to "метка «${example.label}» вне {позитивный, нейтральный, негативный}"
                else -> {
                    val system = example.messages.first().content
                    if (system != TrainingExample.SYSTEM_PROMPT) {
                        errors += lineNo to "system отличается от эталонного TrainingExample.SYSTEM_PROMPT"
                    } else {
                        count++
                        classCounts.merge(example.label, 1, Int::plus)
                    }
                }
            }
        }
        if (count == 0 && errors.isEmpty()) errors += 0 to "файл пуст"
        return if (errors.isEmpty()) ValidationResult.Ok(count, classCounts.toSortedMap())
        else ValidationResult.Failed(errors)
    }

    /** Печатает отчёт по файлу; true — файл валиден. */
    fun report(path: Path): Boolean = when (val result = validate(path)) {
        is ValidationResult.Ok -> {
            println("$path: OK — ${result.count} строк, баланс ${result.classCounts}")
            true
        }
        is ValidationResult.Failed -> {
            println("$path: FAILED — ${result.errors.size} ошибок")
            result.errors.forEach { (line, message) -> println("  строка $line: $message") }
            false
        }
    }
}
