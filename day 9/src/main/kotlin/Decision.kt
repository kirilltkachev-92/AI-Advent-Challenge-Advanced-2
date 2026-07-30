/** Итоговое решение по претензии — строгий enum, никаких свободных строк. */
enum class Decision {
    AUTO_REFUND, MANUAL_REVIEW, REJECT;

    companion object {
        /** null — строка не из enum (оборонительный парсинг ответа LLM). */
        fun parse(raw: String?): Decision? =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
    }
}

/** Решение + id сработавшего правила (R1–R6). */
data class RuleDecision(val decision: Decision, val rule: String)

/**
 * Детерминированная проверка правил R1–R6 на чистом Kotlin — БЕЗ LLM.
 * Принцип дня: LLM решает (этап 2 / монолит), код проверяет — эта функция
 * эталон, с которым сверяется решение модели.
 * Порядок применения: R6 раньше всех — «не претензия» это пустые поля,
 * иначе их перехватил бы R3 «не хватает данных»; дальше R1→R5, первое сработавшее.
 */
object DecisionRules {
    const val AUTO_REFUND_LIMIT = 5000.0

    /**
     * Те же правила словами — единый текст для промптов этапа 2 И монолита:
     * оба варианта получают идентичные правила, сравнение честное.
     */
    val PROMPT_RULES = """
        Правила (проверяй строго по порядку, применяется ПЕРВОЕ сработавшее):
        R6: amt=null И mrc=null И dup=false И frd=false И rcp=false → REJECT (это не претензия вовсе).
        R1: dup=true И amt не null И amt<=5000 → AUTO_REFUND (двойное списание в пределах лимита).
        R2: frd=true → MANUAL_REVIEW (безопасность: заявка о мошенничестве).
        R3: amt=null ИЛИ mrc=null → MANUAL_REVIEW (не хватает данных).
        R4: amt>5000 → MANUAL_REVIEW (крупная сумма).
        R5: иначе → AUTO_REFUND (обычная претензия с полными данными).
    """.trimIndent()

    fun decide(f: ClaimFields): RuleDecision = when {
        // R6: ни одного сигнала претензии — не претензия вовсе.
        // Сознательный edge: cur/dt не учитываются — текст с одной лишь датой/валютой
        // без суммы, мерчанта и флагов трактуем как «не претензия», а не как R3.
        f.amt == null && f.mrc == null && !f.dup && !f.frd && !f.rcp ->
            RuleDecision(Decision.REJECT, "R6")
        // R1: двойное списание в пределах лимита — авто-возврат
        f.dup && f.amt != null && f.amt <= AUTO_REFUND_LIMIT ->
            RuleDecision(Decision.AUTO_REFUND, "R1")
        // R2: заявка о мошенничестве — всегда безопасность/ручной разбор
        f.frd -> RuleDecision(Decision.MANUAL_REVIEW, "R2")
        // R3: не хватает данных (нет суммы или продавца)
        f.amt == null || f.mrc == null -> RuleDecision(Decision.MANUAL_REVIEW, "R3")
        // R4: крупная сумма — ручной разбор
        f.amt > AUTO_REFUND_LIMIT -> RuleDecision(Decision.MANUAL_REVIEW, "R4")
        // R5: обычная претензия с полными данными в пределах лимита
        else -> RuleDecision(Decision.AUTO_REFUND, "R5")
    }
}
