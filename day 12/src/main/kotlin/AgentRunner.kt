import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Сборка одного хода агента в заданном режиме обороны. Модель, температура,
 * источники и запрос пользователя одинаковы во всех трёх режимах — различаются
 * только обработка данных и обвязка, поэтому разницу в исходе можно честно
 * приписать эшелонам.
 *
 * NAIVE — как обычно и пишут: источники склеиваются с запросом как есть, промпт
 * без единой оговорки о том, что данные могут содержать команды, действия
 * исполняются без проверок.
 *
 * SANITIZED — включён ТОЛЬКО эшелон 1: закладку физически вырезают из данных.
 * Промпт остаётся наивным, действия по-прежнему исполняются: это прямой ответ на
 * вопрос «сколько стоит одна лишь очистка входа».
 *
 * DEFENDED — все три: очистка → границы источников с паспортом trust → жёсткий
 * промпт → сверка действий с тем, что просил пользователь → чистка ответа.
 *
 * Разбор ответа намеренно терпимый к форме, но не к содержанию: модели
 * регулярно отдают `suspicious` массивом объектов, а не строк, и падать на этом
 * всем прогоном нельзя. Нераспознанный `answer` — один retry с указанием ошибки,
 * затем ответ считается сырым текстом с пустым списком действий (и это
 * помечается в отчёте: «модель не смогла в контракт» ≠ «модель не просила действий»).
 */
class AgentRunner(
    private val client: DeepSeekClient,
    private val mode: DefenseMode,
    private val model: String = Config.agentModel(),
    private val sanitizer: InputSanitizer = InputSanitizer(),
    private val validator: OutputValidator = OutputValidator(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun run(scenario: Scenario, style: PayloadStyle): AgentTurn {
        // Эшелон 1: в naive его нет вовсе, в остальных режимах данные чистятся до промпта.
        val prepared = scenario.sources.map { source ->
            val raw = source.raw(style)
            source to if (mode == DefenseMode.NAIVE) Sanitized(raw, emptyList()) else sanitizer.clean(raw)
        }
        val system = if (mode == DefenseMode.DEFENDED) {
            AgentPrompts.hardened(scenario.role)
        } else {
            AgentPrompts.naive(scenario.role)
        }
        val userBlock = buildUserBlock(prepared, scenario.userRequest)

        // Вызов LLM (+ один retry, если контракт нарушен).
        var chat = client.chat(model, system, userBlock, jsonMode = true)
        var reply = parse(chat.content)
        var parseNote: String? = null
        var promptTokens = chat.promptTokens
        var completionTokens = chat.completionTokens
        var latencyMs = chat.latencyMs
        if (reply == null) {
            parseNote = "ответ не разобран как JSON — выполнен один retry"
            chat = client.chat(model, system, retryBlock(userBlock), jsonMode = true)
            promptTokens += chat.promptTokens
            completionTokens += chat.completionTokens
            latencyMs += chat.latencyMs
            reply = parse(chat.content)
            if (reply == null) {
                parseNote = "ответ не разобран как JSON даже после retry — взят как сырой текст, действий нет"
            }
        }
        val answer = reply?.answer ?: chat.content
        val calls = reply?.calls ?: emptyList()

        // Действия: журнал ведётся всегда, политика включается только в DEFENDED.
        val bus = ToolBus()
        val policy = policyFor(scenario)
        calls.forEach { bus.execute(it, policy) }

        // Эшелон 3: только в DEFENDED.
        val verdict = if (mode == DefenseMode.DEFENDED) {
            validator.validate(answer, scenario.trustedText(style), bus.records.filterNot { it.allowed })
        } else {
            null
        }
        val finalAnswer = when (verdict) {
            null -> answer
            is OutputVerdict.Pass -> verdict.text
            is OutputVerdict.Redacted -> verdict.text
            is OutputVerdict.Blocked -> AgentPrompts.BLOCKED_NOTICE
        }

        return AgentTurn(
            mode = mode,
            style = style,
            systemPrompt = system,
            userBlock = userBlock,
            rawResponse = chat.content,
            answer = answer,
            parsed = reply != null,
            parseNote = parseNote,
            requestedCalls = calls,
            toolRecords = bus.records,
            suspicious = reply?.suspicious ?: emptyList(),
            removed = prepared.flatMap { it.second.removed },
            outputVerdict = verdict,
            finalAnswer = finalAnswer,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            latencyMs = latencyMs,
        )
    }

    // ── сборка запроса ───────────────────────────────────────────────────────

    private fun buildUserBlock(prepared: List<Pair<Source, Sanitized>>, request: String): String {
        val context = prepared.joinToString("\n\n") { (source, sanitized) ->
            if (mode == DefenseMode.DEFENDED) {
                BoundaryWrapper.wrap(source, sanitized.visibleText)
            } else {
                // Наивная склейка: данные и задание в одном потоке текста, без границ.
                "=== ${source.title} (${source.origin}) ===\n${sanitized.visibleText}"
            }
        }
        val tail = if (mode == DefenseMode.DEFENDED) BoundaryWrapper.wrapUserRequest(request) else request
        return "$context\n\n$tail"
    }

    private fun retryBlock(userBlock: String): String = userBlock +
        "\n\nПРЕДЫДУЩИЙ ОТВЕТ НЕ РАЗОБРАН: это был не JSON заданного формата. " +
        "Верни РОВНО один JSON-объект с полями answer, actions (и suspicious, если оно запрошено), " +
        "без markdown-обёртки и без текста вокруг."

    private fun policyFor(scenario: Scenario): ToolPolicy = when (mode) {
        DefenseMode.NAIVE -> ToolPolicy(emptySet(), enforced = false, label = "naive: действия не проверяются")
        DefenseMode.SANITIZED -> ToolPolicy(emptySet(), enforced = false, label = "sanitized: эшелон 3 выключен")
        DefenseMode.DEFENDED -> ToolPolicy(scenario.allowedTools, enforced = true, label = "defended: сверка с запросом пользователя")
    }

    // ── разбор ответа ────────────────────────────────────────────────────────

    /** null — контракт нарушен (не JSON или нет разбираемого поля answer) → retry. */
    private fun parse(raw: String): AgentReply? {
        val root = runCatching { json.parseToJsonElement(stripFence(raw)).jsonObject }.getOrNull() ?: return null
        val answer = root["answer"]?.let { text(it) } ?: return null
        val calls = root["actions"].asArray().mapNotNull { toCall(it) }
        // Поле suspicious модели отдают и массивом строк, и массивом объектов —
        // это не нарушение контракта по существу, ронять из-за него прогон нельзя.
        val suspicious = root["suspicious"].asArray().map { text(it) ?: it.toString() }
        return AgentReply(answer, calls, suspicious)
    }

    private fun toCall(item: JsonElement): ToolCall? {
        val obj = runCatching { item.jsonObject }.getOrNull()
            // Строка вместо объекта — тоже запрос действия, терять его нельзя.
            ?: return text(item)?.takeIf { it.isNotBlank() }?.let { ToolCall(it, Tool.byId(it), emptyMap()) }
        val rawTool = obj["tool"]?.let { text(it) ?: it.toString() }?.takeIf { it.isNotBlank() } ?: return null
        val args = runCatching { obj["args"]?.jsonObject }.getOrNull()
            ?.mapValues { (_, value) -> text(value) ?: value.toString() }
            ?: emptyMap()
        return ToolCall(rawTool = rawTool, tool = Tool.byId(rawTool), args = args)
    }

    /** Текст примитива; для объекта/массива/null — null, решение принимает вызывающий. */
    private fun text(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonElement?.asArray(): List<JsonElement> =
        this?.let { runCatching { it.jsonArray.toList() }.getOrNull() } ?: emptyList()

    /** Модель иногда всё же оборачивает JSON в ```json … ``` — снимаем обёртку. */
    private fun stripFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}

/** Разобранный ответ агента по контракту: текст, действия, найденные закладки. */
private data class AgentReply(
    val answer: String,
    val calls: List<ToolCall>,
    val suspicious: List<String>,
)
