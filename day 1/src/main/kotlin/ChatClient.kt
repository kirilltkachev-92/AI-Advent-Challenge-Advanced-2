/**
 * Seam для тестируемости: контракт «system + user → ответ LLM».
 * Боевая реализация — DeepSeekClient; в тестах подменяется фейком.
 */
fun interface ChatClient {
    fun chat(system: String, user: String, temperature: Double): String
}
