/**
 * Двухуровневый конвейер «micro-model first»: сначала бесплатный локальный
 * классификатор; большая LLM вызывается ТОЛЬКО если уровень 1 вернул UNSURE
 * (низкая близость, малый отрыв или OOV — см. MicroClassifier). Порядок
 * жёсткий и прозрачный: решение о маршруте принимает структурная уверенность
 * micro-model, LLM не участвует в маршрутизации.
 */
class TwoTierPipeline(
    private val micro: MicroClassifier = MicroClassifier(),
    private val llm: LlmClassifier = LlmClassifier(),
) {
    /** Один запрос через оба уровня; LLM — только при UNSURE. */
    fun route(text: String): RouteResult {
        val microResult = micro.classify(text)
        return if (microResult.status == MicroStatus.OK) {
            RouteResult.MicroHandled(microResult)
        } else {
            RouteResult.LlmHandled(microResult, llm.classify(text))
        }
    }
}
