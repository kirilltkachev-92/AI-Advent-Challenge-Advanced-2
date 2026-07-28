import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Генерация синтетической части датасета через DeepSeek в jsonMode.
 * Батчами по ~10 просит массив {review, label}; в каждый промпт передаёт уже
 * имеющиеся отзывы (чтобы не дублировал) и дефицит по классам (чтобы баланс
 * сходился к равному). Дубли и мусор отсекаются на нашей стороне по каноничному
 * ключу — модели не доверяем.
 *
 * Результат: data/real.jsonl (12 ручных), data/synthetic.jsonl, data/raw.jsonl (объединение).
 */
class DatasetGenerator(
    private val client: DeepSeekClient = DeepSeekClient(),
    private val targetSynthetic: Int = 44,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataDir: Path = Path.of("data")

    fun run() {
        Files.createDirectories(dataDir)
        val real = RealExamples.all
        writeJsonl(dataDir.resolve("real.jsonl"), real)
        println("real.jsonl: ${real.size} ручных примеров")

        val seenKeys = real.map { it.canonicalKey() }.toMutableSet()
        val synthetic = mutableListOf<TrainingExample>()
        var batchNo = 0
        while (synthetic.size < targetSynthetic && batchNo < 12) {
            batchNo++
            val request = batchRequest(real + synthetic)
            if (request.isEmpty()) break
            println("батч $batchNo: прошу ${request.values.sum()} шт. $request …")
            val added = generateBatch(request, real + synthetic, seenKeys)
            synthetic += added
            println("  принято ${added.size}, всего синтетики ${synthetic.size}/$targetSynthetic")
        }
        check(synthetic.size >= targetSynthetic) {
            "за $batchNo батчей набрано только ${synthetic.size}/$targetSynthetic — генерация нестабильна"
        }

        writeJsonl(dataDir.resolve("synthetic.jsonl"), synthetic)
        writeJsonl(dataDir.resolve("raw.jsonl"), real + synthetic)
        val total = real.size + synthetic.size
        println("Итого: $total примеров (${real.size} реальных + ${synthetic.size} синтетических)")
        println("Баланс: ${(real + synthetic).groupingBy { it.label }.eachCount()}")
    }

    /**
     * Дефицит по классам до общего таргета (real + synthetic поровну на три класса),
     * обрезанный до размера батча ~10. Пустая map — генерировать больше нечего.
     */
    private fun batchRequest(current: List<TrainingExample>): Map<String, Int> {
        val totalTarget = RealExamples.all.size + targetSynthetic
        val counts = current.groupingBy { it.label }.eachCount()
        val labels = TrainingExample.VALID_LABELS.toList()
        val quota = totalTarget / labels.size
        val deficits = labels.associateWith { label ->
            (quota + if (labels.indexOf(label) < totalTarget % labels.size) 1 else 0) - (counts[label] ?: 0)
        }.filterValues { it > 0 }.toMutableMap()
        // Урезаем суммарный запрос до 10, снимая по одному с самого «сытого» класса
        while (deficits.values.sum() > 10) {
            val label = deficits.minByOrNull { it.value }!!.key
            if (deficits[label] == 1) deficits.remove(label) else deficits[label] = deficits[label]!! - 1
        }
        return deficits
    }

    /** Один запрос к DeepSeek: jsonMode, фильтрация меток/длины/дублей на выходе. */
    private fun generateBatch(
        request: Map<String, Int>,
        existing: List<TrainingExample>,
        seenKeys: MutableSet<String>,
    ): List<TrainingExample> {
        val existingBrief = existing.joinToString("\n") { "- ${it.reviewText.take(70)}" }
        val system = """
            Ты генерируешь обучающие данные: реалистичные русскоязычные отзывы из мобильных сторов
            на приложения (банки, доставка еды и продуктов, такси и транспорт, маркетплейсы,
            госуслуги, кино, фитнес, каршеринг). Пиши как живые люди: разговорный язык,
            иногда опечатки и строчные буквы, эмоции, конкретные детали (обновление, пуши,
            поддержка, оплата, курьер). Длина — от 1 до 4 предложений, длины чередуй.
            Ответ строго JSON-объектом: {"reviews": [{"review": "текст", "label": "метка"}]}.
            Метка — ровно одно слово: позитивный, нейтральный или негативный.
        """.trimIndent()
        val user = buildString {
            append("Сгенерируй отзывы в количестве: ")
            append(request.entries.joinToString(", ") { "${it.value} с меткой «${it.key}»" })
            append(".\n\nНе повторяй по смыслу и формулировкам уже имеющиеся отзывы:\n")
            append(existingBrief)
        }
        val raw = client.chat(system, user, jsonMode = true, temperature = 1.0)
        val reviews = json.parseToJsonElement(raw).jsonObject["reviews"]?.jsonArray ?: return emptyList()
        val accepted = mutableListOf<TrainingExample>()
        val perLabel = mutableMapOf<String, Int>()
        for (item in reviews) {
            val obj = item.jsonObject
            val review = obj["review"]?.jsonPrimitive?.content?.trim() ?: continue
            val label = obj["label"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: continue
            if (label !in TrainingExample.VALID_LABELS) continue
            if (review.length < 15 || review.length > 600) continue
            // Больше запрошенного по классу не берём — иначе разъедется баланс
            if ((perLabel[label] ?: 0) >= (request[label] ?: 0)) continue
            val key = TrainingExample.canonicalKeyOf(review)
            if (key.isEmpty() || !seenKeys.add(key)) continue
            perLabel.merge(label, 1, Int::plus)
            accepted += TrainingExample.of(review, label)
        }
        return accepted
    }

    private fun writeJsonl(path: Path, examples: List<TrainingExample>) {
        Files.write(path, examples.map { json.encodeToString(it) })
    }
}
