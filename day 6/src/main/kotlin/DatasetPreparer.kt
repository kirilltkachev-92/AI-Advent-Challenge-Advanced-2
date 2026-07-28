import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Очистка и разбиение датасета: real.jsonl + synthetic.jsonl → train.jsonl + eval.jsonl.
 * Отбраковка (дубли по каноничному ключу, пустые, <15 и >600 символов, невалидные метки)
 * ведётся со счётчиками причин — отчёт печатается всегда, «молчаливой» чистки нет.
 * Shuffle детерминированный (Random(42)), split 80/20 стратифицированный по классам —
 * баланс train и eval совпадает с балансом исходника.
 */
class DatasetPreparer(private val seed: Long = 42L) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dataDir: Path = Path.of("data")

    fun run() {
        val sources = listOf("real.jsonl", "synthetic.jsonl").map { dataDir.resolve(it) }
        sources.forEach { check(Files.exists(it)) { "$it не найден — сначала ./run.sh generate" } }
        val examples = sources.flatMap { path ->
            Files.readAllLines(path).filter { it.isNotBlank() }.map { json.decodeFromString<TrainingExample>(it) }
        }
        println("Прочитано: ${examples.size} примеров из ${sources.joinToString { it.fileName.toString() }}")

        // ── очистка со счётчиками причин ──
        val reasons = linkedMapOf(
            "дубль (каноничный ключ)" to 0,
            "пустой отзыв или метка" to 0,
            "слишком короткий (<15 символов)" to 0,
            "слишком длинный (>600 символов)" to 0,
            "невалидная метка" to 0,
        )
        val seenKeys = mutableSetOf<String>()
        val clean = examples.filter { example ->
            val review = example.reviewText.trim()
            val reason = when {
                review.isEmpty() || example.label.isBlank() -> "пустой отзыв или метка"
                review.length < 15 -> "слишком короткий (<15 символов)"
                review.length > 600 -> "слишком длинный (>600 символов)"
                example.label !in TrainingExample.VALID_LABELS -> "невалидная метка"
                !seenKeys.add(example.canonicalKey()) -> "дубль (каноничный ключ)"
                else -> null
            }
            if (reason != null) reasons.merge(reason, 1, Int::plus)
            reason == null
        }
        println("После очистки: ${clean.size} (отброшено ${examples.size - clean.size})")
        reasons.filterValues { it > 0 }.forEach { (reason, count) -> println("  - $reason: $count") }

        // ── детерминированный shuffle + стратифицированный split 80/20 ──
        val train = mutableListOf<TrainingExample>()
        val eval = mutableListOf<TrainingExample>()
        clean.groupBy { it.label }.toSortedMap().forEach { (_, group) ->
            val shuffled = group.shuffled(Random(seed))
            val evalCount = maxOf(1, Math.round(shuffled.size * 0.2).toInt())
            eval += shuffled.take(evalCount)
            train += shuffled.drop(evalCount)
        }
        val trainShuffled = train.shuffled(Random(seed))
        val evalShuffled = eval.shuffled(Random(seed))
        writeJsonl(dataDir.resolve("train.jsonl"), trainShuffled)
        writeJsonl(dataDir.resolve("eval.jsonl"), evalShuffled)

        println("train.jsonl: ${trainShuffled.size} — баланс ${balance(trainShuffled)}")
        println("eval.jsonl:  ${evalShuffled.size} — баланс ${balance(evalShuffled)}")
    }

    private fun balance(examples: List<TrainingExample>): Map<String, Int> =
        examples.groupingBy { it.label }.eachCount().toSortedMap()

    private fun writeJsonl(path: Path, examples: List<TrainingExample>) {
        Files.write(path, examples.map { json.encodeToString(it) })
    }
}
