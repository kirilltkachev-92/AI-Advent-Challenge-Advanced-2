import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Вариант B — конвейер из трёх коротких этапов, каждый со строгим компактным форматом.
 * Контракт: зашумлённый текст претензии → PipelineResult.
 * Точка декомпозиции: этап 2 получает ТОЛЬКО компактный JSON этапа 1, исходный
 * текст туда не передаётся — правила применяются к проверяемой структуре,
 * а не к шуму; этап 3 тоже видит только поля + решение, не текст.
 * Модель каждого этапа конфигурируется отдельно (STAGE1..3_MODEL) — каждый этап
 * достаточно прост для маленькой дешёвой модели.
 */
class StagePipeline(
    private val client: DeepSeekClient = DeepSeekClient(),
    private val stage1Model: String = Config.stage1Model(),
    private val stage2Model: String = Config.stage2Model(),
    private val stage3Model: String = Config.stage3Model(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun process(text: String): PipelineResult {
        // ── Этап 1 «Нормализация»: текст → компактные поля ──────────────────
        val s1Call = runCatching { client.chat(stage1Model, STAGE1_SYSTEM, text, jsonMode = true) }
        val s1 = s1Call.getOrNull()
            ?: return PipelineResult.Failed("этап 1", transport(s1Call))
        val m1 = CallMetrics.of(s1)
        val fields = ClaimFields.fromRaw(s1.content)
            ?: return PipelineResult.Failed("этап 1", "невалидный JSON: ${s1.content.take(200)}", stage1Metrics = m1)

        // ── Этап 2 «Решение»: ТОЛЬКО компактный JSON полей, без исходного текста ──
        val compact = fields.toCompactJson()
        val s2Call = runCatching { client.chat(stage2Model, STAGE2_SYSTEM, compact, jsonMode = true) }
        val s2 = s2Call.getOrNull()
            ?: return PipelineResult.Failed("этап 2", transport(s2Call), fields, m1)
        val m2 = CallMetrics.of(s2)
        val stage2Decision = parseStage2(s2.content)
            ?: return PipelineResult.Failed("этап 2", "невалидный ответ: ${s2.content.take(200)}", fields, m1, m2)

        // ── Этап 3 «Формирование результата»: поля + решение, снова без текста ──
        val stage3Input = buildJsonObject {
            put("fields", json.parseToJsonElement(compact))
            put("dec", stage2Decision.decision.name)
            put("rule", stage2Decision.rule)
        }.toString()
        val s3Call = runCatching { client.chat(stage3Model, STAGE3_SYSTEM, stage3Input, jsonMode = true) }
        val s3 = s3Call.getOrNull()
            ?: return PipelineResult.Failed("этап 3", transport(s3Call), fields, m1, m2)
        val m3 = CallMetrics.of(s3)
        val final = parseStage3(s3.content)
            ?: return PipelineResult.Failed("этап 3", "невалидный ответ: ${s3.content.take(200)}", fields, m1, m2, m3)

        return PipelineResult.Done(
            fields = fields,
            stage1Raw = s1.content,
            stage2Decision = stage2Decision,
            stage2Raw = s2.content,
            finalDecision = final.first,
            message = final.second,
            stage3Raw = s3.content,
            stage1Metrics = m1,
            stage2Metrics = m2,
            stage3Metrics = m3,
        )
    }

    // ── оборонительный парсинг строгих форматов этапов ──────────────────────

    private fun parseStage2(raw: String): RuleDecision? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val dec = Decision.parse(runCatching { obj["dec"]?.jsonPrimitive?.contentOrNull }.getOrNull()) ?: return null
        val rule = runCatching { obj["rule"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.uppercase() ?: return null
        if (!Regex("""R[1-6]""").matches(rule)) return null
        return RuleDecision(dec, rule)
    }

    private fun parseStage3(raw: String): Pair<Decision, String>? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val decision = Decision.parse(runCatching { obj["decision"]?.jsonPrimitive?.contentOrNull }.getOrNull()) ?: return null
        val message = runCatching { obj["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return decision to message
    }

    private fun transport(call: Result<ChatResult>): String =
        "транспорт: ${call.exceptionOrNull()?.message?.take(200)}"

    companion object {
        /** Этап 1 — ТОЛЬКО извлечение, никаких решений: короткий промпт, компактный выход. */
        val STAGE1_SYSTEM = """
            Ты — нормализатор претензий клиентов мобильного банка (возвраты и спорные списания).
            Извлеки поля из зашумлённого текста клиента. Делай ТОЛЬКО извлечение —
            никаких решений, оценок и рекомендаций.
            Верни строго компактный JSON одной строкой с короткими ключами:
            {"amt": число или null — сумма спорного списания,
             "cur": "RUB"|"USD"|"EUR"|null — валюта («руб», «рэ», «рублей» → RUB),
             "mrc": "название продавца/магазина" или null,
             "dt": "YYYY-MM-DD" или null — дата операции,
             "rcp": true|false — упомянут чек/квитанция/подтверждение,
             "dup": true|false — клиент говорит о двойном/повторном списании,
             "frd": true|false — клиент заявляет о мошенничестве или что операцию не совершал}
            Чего в тексте нет — null/false. Ничего не выдумывай и не додумывай.
        """.trimIndent()

        /** Этап 2 — только правила над полями: входного текста клиента здесь НЕТ. */
        val STAGE2_SYSTEM = """
            Ты — движок решений по претензиям в мобильном банке.
            На входе — ТОЛЬКО компактный JSON полей претензии (исходного текста нет):
            amt — сумма, cur — валюта, mrc — продавец, dt — дата,
            rcp — есть подтверждение/чек, dup — двойное списание, frd — заявка о мошенничестве.
            ${DecisionRules.PROMPT_RULES}
            Верни строго JSON одной строкой: {"dec": "AUTO_REFUND"|"MANUAL_REVIEW"|"REJECT", "rule": "R1".."R6"}.
            Никаких других полей и пояснений.
        """.trimIndent()

        /** Этап 3 — только формулировка ответа: решение уже принято, менять нельзя. */
        val STAGE3_SYSTEM = """
            Ты — формирователь финального ответа по претензии клиента мобильного банка.
            На входе JSON: fields — поля претензии, dec — уже принятое решение, rule — сработавшее правило.
            Решение НЕ пересматривай и НЕ меняй.
            Верни строго JSON: {"decision": "<dec как есть>",
            "message": "<1-2 вежливых предложения клиенту по-русски: что решено и что будет дальше; без внутренних кодов правил>",
            "fields": <объект fields как есть>}.
        """.trimIndent()
    }
}
