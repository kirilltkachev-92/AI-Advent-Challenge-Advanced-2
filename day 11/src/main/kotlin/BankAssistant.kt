/**
 * Ассистент-жертва в трёх режимах. Модель, температура и текст атаки одинаковы —
 * различаются только оборона и системный промпт, поэтому разницу в исходе можно
 * честно приписать эшелонам.
 *
 * NAIVE — как обычно и пишут: короткий промпт с секретами и сырая склейка
 * «документ + текст пользователя», без проверок; ответ модели уходит клиенту как есть.
 *
 * PROMPT_ONLY — включён ТОЛЬКО эшелон 2: жёсткий промпт и разделители, но ни
 * валидации входа, ни guard на выходе. Это прямой ответ на вопрос задания
 * «устоял ли сам системный промпт»: атака гарантированно доходит до модели,
 * и удержать её может только текст промпта.
 *
 * HARDENED — три эшелона по порядку:
 *   1) input validation: Blocked → до модели не доходим вовсе (0 токенов);
 *      документ проходит тот же гейт, при Blocked его тело заменяется заглушкой;
 *   2) prompt hardening: жёсткий системный промпт + разделители, данные внутри
 *      разделителей объявлены данными;
 *   3) output guard: Blocked → клиенту уходит стандартный отказ.
 *
 * `stoppedBy = PROMPT` здесь не выставляется: «промпт удержал» можно утверждать
 * только после BreachDetector, который знает маркеры конкретной атаки, — это
 * делает RedTeamRun поверх готового AssistantTurn.
 */
class BankAssistant(
    private val client: DeepSeekClient,
    private val mode: DefenseMode,
    private val model: String = Config.attackModel(),
    private val validator: InputValidator = InputValidator(),
    private val guard: OutputGuard = OutputGuard(),
) {

    fun answer(userText: String, docId: String?): AssistantTurn {
        val doc = docId?.let { RetrievedDocs.byId(it) }
        return when (mode) {
            DefenseMode.NAIVE -> answerNaive(userText, doc)
            DefenseMode.PROMPT_ONLY -> answerPromptOnly(userText, doc)
            DefenseMode.HARDENED -> answerHardened(userText, doc)
        }
    }

    // ── режимы ───────────────────────────────────────────────────────────────

    private fun answerNaive(userText: String, doc: String?): AssistantTurn {
        // Никаких разделителей и проверок: документ и запрос — один текст.
        val userBlock = if (doc != null) "$doc\n\n$userText" else userText
        val chat = client.chat(model, SystemPrompts.naive, userBlock)
        return AssistantTurn(
            mode = mode,
            inputVerdict = null,
            docVerdict = null,
            systemPrompt = SystemPrompts.naive,
            userBlock = userBlock,
            rawAnswer = chat.content,
            finalAnswer = chat.content,
            stoppedBy = null,
            guardReasons = emptyList(),
            promptTokens = chat.promptTokens,
            completionTokens = chat.completionTokens,
            latencyMs = chat.latencyMs,
        )
    }

    private fun answerPromptOnly(userText: String, doc: String?): AssistantTurn {
        // Ни валидации входа, ни guard на выходе: документ и payload идут в модель как есть,
        // держать атаку может только текст системного промпта и разделители.
        val userBlock = wrapInDelimiters(doc, userText)
        val chat = client.chat(model, SystemPrompts.hardened, userBlock)
        return AssistantTurn(
            mode = mode,
            inputVerdict = null,
            docVerdict = null,
            systemPrompt = SystemPrompts.hardened,
            userBlock = userBlock,
            rawAnswer = chat.content,
            finalAnswer = chat.content,
            stoppedBy = null,
            guardReasons = emptyList(),
            promptTokens = chat.promptTokens,
            completionTokens = chat.completionTokens,
            latencyMs = chat.latencyMs,
        )
    }

    private fun answerHardened(userText: String, doc: String?): AssistantTurn {
        val inputVerdict = validator.check(userText)
        if (inputVerdict is InputVerdict.Blocked) {
            return AssistantTurn(
                mode = mode,
                inputVerdict = inputVerdict,
                docVerdict = null,
                systemPrompt = SystemPrompts.hardened,
                userBlock = "[запрос отклонён input-валидатором: ${inputVerdict.labels.joinToString(", ")}] " +
                    "— вызов LLM не выполнялся",
                rawAnswer = null,
                finalAnswer = SystemPrompts.REFUSAL,
                stoppedBy = Echelon.INPUT,
                guardReasons = emptyList(),
                promptTokens = 0,
                completionTokens = 0,
                latencyMs = 0,
            )
        }

        val docVerdict = doc?.let { validator.check(it) }
        val docBody = when (docVerdict) {
            null -> null
            is InputVerdict.Blocked -> "[документ отклонён input-валидатором: ${docVerdict.labels.joinToString(", ")}]"
            is InputVerdict.Suspicious -> docVerdict.sanitized
            is InputVerdict.Clean -> docVerdict.sanitized
        }
        val userSanitized = when (inputVerdict) {
            is InputVerdict.Clean -> inputVerdict.sanitized
            is InputVerdict.Suspicious -> inputVerdict.sanitized
            is InputVerdict.Blocked -> error("Blocked обработан выше")
        }

        val userBlock = wrapInDelimiters(docBody, userSanitized)
        val chat = client.chat(model, SystemPrompts.hardened, userBlock)
        val verdict = guard.inspect(chat.content)
        val finalAnswer = when (verdict) {
            is GuardVerdict.Pass -> verdict.text
            is GuardVerdict.Redacted -> verdict.text
            is GuardVerdict.Blocked -> SystemPrompts.REFUSAL
        }
        val guardReasons = when (verdict) {
            is GuardVerdict.Pass -> emptyList()
            is GuardVerdict.Redacted -> verdict.reasons
            is GuardVerdict.Blocked -> verdict.reasons
        }
        // Документ зарезан на входе — значит вредоносная инструкция до модели не дошла,
        // даже если сам вызов LLM состоялся (текст пользователя был безобидным).
        val stoppedBy = when {
            verdict is GuardVerdict.Blocked -> Echelon.OUTPUT
            docVerdict is InputVerdict.Blocked -> Echelon.INPUT
            else -> null
        }
        return AssistantTurn(
            mode = mode,
            inputVerdict = inputVerdict,
            docVerdict = docVerdict,
            systemPrompt = SystemPrompts.hardened,
            userBlock = userBlock,
            rawAnswer = chat.content,
            finalAnswer = finalAnswer,
            stoppedBy = stoppedBy,
            guardReasons = guardReasons,
            promptTokens = chat.promptTokens,
            completionTokens = chat.completionTokens,
            latencyMs = chat.latencyMs,
        )
    }

    // ── помощники ────────────────────────────────────────────────────────────

    /** Эшелон 2 в чистом виде: документ и текст пользователя помечены как ДАННЫЕ. */
    private fun wrapInDelimiters(docBody: String?, userText: String): String = buildString {
        if (docBody != null) {
            appendLine(SystemPrompts.DOC_START)
            appendLine(docBody)
            appendLine(SystemPrompts.DOC_END)
        }
        appendLine(SystemPrompts.USER_START)
        appendLine(userText)
        append(SystemPrompts.USER_END)
    }
}

/** Режим обороны: одна и та же модель, разные промпт и обвязка. */
enum class DefenseMode(val label: String) {
    NAIVE("naive"),
    PROMPT_ONLY("hardened (только промпт)"),
    HARDENED("hardened (все эшелоны)"),
}

/** Какой эшелон остановил атаку; null — ничего не остановило. */
enum class Echelon(val label: String) {
    INPUT("input validation"),
    PROMPT("prompt hardening"),
    OUTPUT("output guard"),
}

/** Один ход ассистента: что ушло в модель, что она ответила, что увидел клиент. */
data class AssistantTurn(
    val mode: DefenseMode,
    /** null в NAIVE — проверки нет вовсе. */
    val inputVerdict: InputVerdict?,
    /** Вердикт по подтянутому документу (для indirect-атак); null — документа не было. */
    val docVerdict: InputVerdict?,
    val systemPrompt: String,
    /** То, что реально ушло в user-сообщение. */
    val userBlock: String,
    /** null, если запрос не дошёл до модели. */
    val rawAnswer: String?,
    /** То, что увидел бы клиент. */
    val finalAnswer: String,
    val stoppedBy: Echelon?,
    val guardReasons: List<String>,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
