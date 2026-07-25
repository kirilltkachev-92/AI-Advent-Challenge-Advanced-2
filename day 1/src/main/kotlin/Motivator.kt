/**
 * Результат попытки получить мотивационную фразу. Sealed вместо исключений:
 * недоступность LLM — ожидаемый исход бизнес-операции, а не сбой программы.
 */
sealed interface MotivationResult {
    data class Done(val phrase: String) : MotivationResult
    data class Failed(val reason: String) : MotivationResult
}

/**
 * Доменная логика мотиватора: задача пользователя → короткая мотивационная
 * фраза от LLM. Промпт жёстко ограничивает формат (1–2 предложения, без
 * кавычек и преамбул), temperature повышена — фразы должны быть живыми.
 *
 * Исключения транспорта (ChatClient может бросать) гасятся здесь и
 * превращаются в [MotivationResult.Failed] — наружу исключения не летят.
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
            MotivationResult.Done(client.chat(system, task, temperature = 1.1).trim().removeSurrounding("\""))
        } catch (e: Exception) {
            MotivationResult.Failed(e.message ?: "DeepSeek недоступен")
        }
}
