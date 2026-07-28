import kotlinx.serialization.Serializable

/**
 * Одна строка датасета в формате fine-tune OpenAI: ровно три сообщения
 * system → user → assistant. System одинаков во всех примерах — модель учится
 * только маппингу «текст отзыва → метка», а не разным инструкциям.
 */
@Serializable
data class TrainingExample(val messages: List<Message>) {

    /** Текст отзыва — content первого user-сообщения. */
    val reviewText: String
        get() = messages.firstOrNull { it.role == "user" }?.content ?: ""

    /** Эталонная метка — content последнего assistant-сообщения. */
    val label: String
        get() = messages.lastOrNull { it.role == "assistant" }?.content ?: ""

    /**
     * Каноничный ключ для дедупликации: lowercase, без пунктуации, пробелы схлопнуты.
     * «Отличное приложение!» и «отличное приложение» — один и тот же пример.
     */
    fun canonicalKey(): String = canonicalKeyOf(reviewText)

    companion object {
        const val SYSTEM_PROMPT =
            "Ты — классификатор тональности отзывов на мобильные приложения. " +
                "Ответь ровно одним словом: позитивный, нейтральный или негативный."

        val VALID_LABELS = setOf("позитивный", "нейтральный", "негативный")

        /** Собирает пример из пары «отзыв → метка» с единым system-промптом. */
        fun of(review: String, label: String) = TrainingExample(
            listOf(
                Message("system", SYSTEM_PROMPT),
                Message("user", review),
                Message("assistant", label),
            )
        )

        /** Каноничный ключ для произвольного текста (нужен и до создания примера). */
        fun canonicalKeyOf(text: String): String = text
            .lowercase()
            .replace(Regex("[\\p{Punct}«»…—]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** Сообщение чата в терминах OpenAI-совместимого протокола. */
@Serializable
data class Message(val role: String, val content: String)
