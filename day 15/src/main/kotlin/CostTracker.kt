import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Учёт стоимости: цены за 1M токенов берутся из Config (приблизительные,
 * переопределяются через env). Копит итоги сессии для /v1/audit.
 */
class CostTracker(
    private val priceInputPer1M: Double = Config.priceInputPer1M(),
    private val priceOutputPer1M: Double = Config.priceOutputPer1M(),
) {
    @Serializable
    data class Totals(
        val requests: Long,
        @SerialName("prompt_tokens") val promptTokens: Long,
        @SerialName("completion_tokens") val completionTokens: Long,
        @SerialName("cost_usd") val costUsd: Double,
    )

    private var requests = 0L
    private var promptTokens = 0L
    private var completionTokens = 0L
    private var costUsd = 0.0

    /** Стоимость одного запроса в USD; попутно копится в итогах сессии. */
    @Synchronized
    fun track(prompt: Int, completion: Int): Double {
        val cost = round8(prompt * priceInputPer1M / 1_000_000 + completion * priceOutputPer1M / 1_000_000)
        requests += 1
        promptTokens += prompt
        completionTokens += completion
        costUsd += cost
        return cost
    }

    @Synchronized
    fun totals(): Totals = Totals(requests, promptTokens, completionTokens, round8(costUsd))

    /** Округление до 8 знаков: срезает накопленный шум сложения; последний бит double может остаться. */
    private fun round8(value: Double): Double = Math.round(value * 1e8) / 1e8
}
