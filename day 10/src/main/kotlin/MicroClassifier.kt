/**
 * Уровень 1 — micro-model: TF-IDF nearest-centroid классификатор на чистом
 * Kotlin, БЕЗ сети и БЕЗ LLM. Строится по seed-корпусу при создании:
 * каждый интент = нормированный центроид TF-IDF-векторов его примеров,
 * классификация = косинусная близость запроса к центроидам.
 *
 * Уверенность СТРУКТУРНАЯ, а не самооценка модели (маленькие модели рапортуют
 * 100% уверенности — эффект Даннинга–Крюгера): score = top1 * (1 + margin) / 2,
 * где top1 — близость к лучшему центроиду, margin = top1 − top2 (отрыв от
 * второго). Формула объяснима: высокий top1 без отрыва даёт ~top1/2 —
 * «похоже сразу на два интента», а top1 с большим отрывом почти не штрафуется.
 *
 * UNSURE (→ LLM-fallback), если хоть одно из:
 *  - знакомых словарю токенов < 2 (OOV-защита: сленг/опечатки вне корпуса);
 *  - top1 < SIM_FLOOR (даже лучший интент далёк — вектор почти чужой);
 *  - score < threshold (Config.CONFIDENCE_THRESHOLD, по умолчанию 0.20).
 */
class MicroClassifier(
    seeds: Map<Intent, List<String>> = IntentSeeds.all,
    private val threshold: Double = Config.confidenceThreshold(),
) {
    private val idf: Map<String, Double>
    private val centroids: Map<Intent, Map<String, Double>>

    init {
        // Документ = одна seed-фраза; IDF со сглаживанием: ln((1+N)/(1+df)) + 1.
        val docs = seeds.flatMap { (intent, phrases) -> phrases.map { intent to Tokenizer.tokenize(it) } }
        val df = mutableMapOf<String, Int>()
        docs.forEach { (_, tokens) -> tokens.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size
        idf = df.mapValues { (_, d) -> Math.log((1.0 + n) / (1.0 + d)) + 1.0 }
        centroids = docs.groupBy({ it.first }, { it.second }).mapValues { (_, tokenLists) ->
            val sum = mutableMapOf<String, Double>()
            tokenLists.forEach { tokens ->
                vectorOf(tokens).forEach { (t, w) -> sum[t] = (sum[t] ?: 0.0) + w }
            }
            normalize(sum)
        }
    }

    /** Классификация одного текста; латентность меряется в микросекундах — в этом и суть уровня 1. */
    fun classify(text: String): MicroResult {
        val started = System.nanoTime()
        val tokens = Tokenizer.tokenize(text)
        val knownTokens = tokens.count { it in idf }
        val vector = vectorOf(tokens)
        val sims = centroids
            .map { (intent, centroid) -> IntentSim(intent, dot(vector, centroid)) }
            .sortedByDescending { it.similarity }
        val top1 = sims[0].similarity
        val margin = top1 - sims[1].similarity
        val score = top1 * (1.0 + margin) / 2.0
        val status = when {
            knownTokens < MIN_KNOWN_TOKENS -> MicroStatus.UNSURE
            top1 < SIM_FLOOR -> MicroStatus.UNSURE
            score < threshold -> MicroStatus.UNSURE
            else -> MicroStatus.OK
        }
        val latencyMicros = (System.nanoTime() - started) / 1_000
        return MicroResult(sims[0].intent, score, status, sims, margin, tokens, knownTokens, latencyMicros)
    }

    // ── векторная арифметика ────────────────────────────────────────────────

    /** TF-IDF-вектор документа, нормированный до единичной длины. */
    private fun vectorOf(tokens: List<String>): Map<String, Double> {
        val tf = tokens.filter { it in idf }.groupingBy { it }.eachCount()
        return normalize(tf.mapValues { (t, c) -> c * idf.getValue(t) })
    }

    private fun normalize(v: Map<String, Double>): Map<String, Double> {
        val norm = Math.sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) v else v.mapValues { it.value / norm }
    }

    /** Оба вектора нормированы, поэтому скалярное произведение = косинус. */
    private fun dot(a: Map<String, Double>, b: Map<String, Double>): Double =
        a.entries.sumOf { (t, w) -> w * (b[t] ?: 0.0) }

    companion object {
        /** Абсолютный пол близости top1: ниже — «вектор чужой», сразу UNSURE. */
        const val SIM_FLOOR = 0.30

        /** OOV-защита: меньше двух знакомых токенов — классификации не верим. */
        const val MIN_KNOWN_TOKENS = 2
    }
}

/** Статус уверенности micro-model: OK — берём ответ, UNSURE — уходим в LLM. */
enum class MicroStatus { OK, UNSURE }

/** Близость запроса к центроиду одного интента. */
data class IntentSim(val intent: Intent, val similarity: Double)

/**
 * Структурированный результат уровня 1: метка + score + статус (контракт задачи),
 * плюс вся внутренность для отчёта — близости по интентам, отрыв, токены.
 */
data class MicroResult(
    val label: Intent,
    val score: Double,
    val status: MicroStatus,
    val sims: List<IntentSim>,
    val margin: Double,
    val tokens: List<String>,
    val knownTokens: Int,
    val latencyMicros: Long,
)
