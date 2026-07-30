import kotlinx.serialization.Serializable

/**
 * Извлечённое платёжное поручение. Все поля nullable намеренно:
 * модель обязана ставить null там, где не уверена, — а не угадывать,
 * потому что дальше null ловится constraint-проверками и кейс отклоняется.
 */
@Serializable
data class TransferOrder(
    val amount: Double? = null,
    val currency: String? = null,
    val recipient: String? = null,
) {
    /**
     * Каноническая форма для сравнения ответов при redundancy-голосовании:
     * валюта — верхним регистром, получатель — lowercase/trim/схлопнутые пробелы.
     * Два ответа считаются совпавшими, если равны их канонические формы.
     */
    fun canonical(): String {
        val recipientNorm = recipient?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")
        return "${amount}|${currency?.trim()?.uppercase()}|$recipientNorm"
    }
}
