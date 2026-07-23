/**
 * Доменная логика: задача пользователя → короткая мотивационная фраза.
 * Формат ответа держит промпт (1–2 предложения, без кавычек и преамбул),
 * а `sanitize` подчищает типовые огрехи модели — контракт наружу стабилен.
 */
class Motivator(private val client: DeepSeekClient) {

    fun motivate(task: String): MotivationResult =
        try {
            MotivationResult.Done(sanitize(client.chat(SYSTEM_PROMPT, task.trim())))
        } catch (e: Exception) {
            MotivationResult.Failed(e.message ?: e.javaClass.simpleName)
        }

    private fun sanitize(raw: String): String =
        raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("«", "»")
            .replace(Regex("\\s+"), " ")
            .take(300)

    private companion object {
        val SYSTEM_PROMPT = """
            Ты — краткий и энергичный мотиватор. Пользователь присылает описание задачи,
            которую ему предстоит сделать. Ответь ОДНОЙ мотивационной фразой на русском:
            1–2 предложения, максимум 200 символов, по существу задачи, без общих банальностей.
            Без кавычек, без преамбул вида «Вот фраза:», без эмодзи-спама (максимум один эмодзи).
        """.trimIndent()
    }
}

/** Результат мотивации: фраза либо причина отказа (LLM недоступна и т.п.). */
sealed interface MotivationResult {
    data class Done(val phrase: String) : MotivationResult
    data class Failed(val reason: String) : MotivationResult
}
