/**
 * Результат генерации мотивации: фраза либо причина отказа.
 * Исключения транспорта (ChatClient) не выходят за пределы домена —
 * они превращаются в Failed, а HTTP-слой мапит его в код ответа.
 */
sealed interface MotivationResult {
    data class Done(val phrase: String) : MotivationResult
    data class Failed(val reason: String) : MotivationResult
}

/**
 * Доменная логика мотиватора: задача пользователя → короткая мотивационная
 * фраза от LLM. Промпт жёстко ограничивает формат (1–2 предложения, без
 * кавычек и преамбул), temperature повышена — фразы должны быть живыми.
 */
class Motivator(private val client: ChatClient) {

    private val system = """
        Ты — краткий и энергичный мотиватор для разработчика.
        Тебе дают описание задачи. Ответь ОДНОЙ мотивационной фразой
        (1–2 предложения, до 200 символов) на языке задачи.
        Фраза должна цеплять и быть привязана к сути задачи, а не быть общей банальностью.
        Без кавычек, без преамбул, без списков, без эмодзи.
    """.trimIndent()

    fun motivate(task: String): MotivationResult =
        try {
            MotivationResult.Done(sanitize(client.chat(system, task, temperature = 1.1)))
        } catch (e: Exception) {
            MotivationResult.Failed(e.message ?: "DeepSeek недоступен")
        }

    /**
     * Чистка сырого ответа LLM: множественные пробелы/переводы строк
     * схлопываются в один пробел, затем снимаются обрамляющие кавычки —
     * двойные ("…") и «ёлочки». Кавычки внутри фразы не трогаются.
     */
    private fun sanitize(raw: String): String =
        raw.replace(whitespaceRun, " ")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("«", "»")
            .trim()

    private companion object {
        val whitespaceRun = Regex("\\s+")
    }
}
