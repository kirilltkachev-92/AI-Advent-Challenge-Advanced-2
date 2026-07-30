import java.math.BigDecimal

/**
 * Подход №2 — жёсткие ограничения предметной области, чистая функция без I/O.
 * Деньги двигать по догадке нельзя, поэтому проверки бинарные:
 * сумма положительная, конечная, ≤ 2 знаков после запятой и не выше лимита;
 * валюта из белого списка; получатель непустой, не плейсхолдер и не «простыня».
 * Возвращает список именованных нарушений; пустой список = проверка пройдена.
 */
class ConstraintChecker(
    private val amountLimit: Double = 1_000_000.0,
) {
    private val allowedCurrencies = setOf("RUB", "USD", "EUR")
    private val placeholders = setOf(
        "не указан", "не указано", "неизвестно", "неизвестен", "нет",
        "unknown", "none", "null", "n/a", "-", "—", "?", "получатель",
    )

    fun check(order: TransferOrder): List<String> {
        val violations = mutableListOf<String>()

        val amount = order.amount
        when {
            amount == null -> violations += "amount_missing: сумма не извлечена"
            !amount.isFinite() -> violations += "amount_not_finite: сумма не является числом"
            amount <= 0 -> violations += "amount_not_positive: сумма должна быть больше нуля"
            amount > amountLimit -> violations += "amount_over_limit: сумма выше лимита ${amountLimit.toLong()}"
            decimalPlaces(amount) > 2 -> violations += "amount_precision: больше двух знаков после запятой"
        }

        val currency = order.currency?.trim()?.uppercase()
        when {
            currency.isNullOrBlank() -> violations += "currency_missing: валюта не извлечена"
            currency !in allowedCurrencies -> violations += "currency_unknown: «$currency» не из списка $allowedCurrencies"
        }

        val recipient = order.recipient?.trim()
        when {
            recipient.isNullOrBlank() -> violations += "recipient_missing: получатель не извлечён"
            recipient.lowercase() in placeholders -> violations += "recipient_placeholder: «$recipient» — заглушка, а не получатель"
            recipient.length > 100 -> violations += "recipient_too_long: длиннее 100 символов"
        }

        return violations
    }

    private fun decimalPlaces(amount: Double): Int =
        runCatching { BigDecimal(amount.toString()).stripTrailingZeros().scale() }.getOrDefault(0)
}
