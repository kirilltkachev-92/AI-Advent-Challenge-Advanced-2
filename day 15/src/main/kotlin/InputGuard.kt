/** Режим обработки найденных секретов во входящем промпте. */
enum class GuardMode {
    BLOCK, MASK;

    companion object {
        /** null — неизвестный режим (валидируется HTTP-слоем как 400). */
        fun parse(raw: String?): GuardMode? = when (raw?.lowercase()) {
            null, "block" -> BLOCK
            "mask" -> MASK
            else -> null
        }
    }
}

/** Итог проверки входа: что уйдёт (или не уйдёт) в LLM. */
sealed interface GuardVerdict {
    /** Секретов нет — промпт уходит как есть. */
    data class Clean(val prompt: String) : GuardVerdict

    /** Режим block: секрет найден, в LLM не уходит НИЧЕГО. */
    data class Blocked(val findings: List<Finding>) : GuardVerdict

    /** Режим mask: каждый секрет заменён на [REDACTED_*], остальное сохранено. */
    data class Masked(val maskedPrompt: String, val findings: List<Finding>) : GuardVerdict
}

/**
 * Входной страж шлюза: сканирует промпт до любого обращения к LLM.
 * Контракт: block — находка блокирует запрос целиком; mask — секреты
 * заменяются типизированными placeholder'ами, и в LLM уходит только маска.
 */
class InputGuard(private val scanner: SecretScanner = SecretScanner()) {

    fun inspect(prompt: String, mode: GuardMode): GuardVerdict {
        val findings = scanner.scan(prompt)
        return when {
            findings.isEmpty() -> GuardVerdict.Clean(prompt)
            mode == GuardMode.BLOCK -> GuardVerdict.Blocked(findings)
            else -> GuardVerdict.Masked(scanner.maskAll(prompt, findings), findings)
        }
    }
}
