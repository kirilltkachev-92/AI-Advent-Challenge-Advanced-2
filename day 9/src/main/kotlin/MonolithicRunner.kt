import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Вариант A — монолит: один большой промпт (извлечение полей + те же правила
 * R1–R6 + тон ответа) → один вызов LLM → сразу финальный JSON.
 * Требует ту же форму результата и тот же текст правил, что и конвейер, —
 * сравнение честное. Поля из ответа тоже парсятся: по ним кодовая проверка
 * выясняет, следовал ли монолит собственным правилам.
 */
class MonolithicRunner(
    private val client: DeepSeekClient = DeepSeekClient(),
    private val model: String = Config.monoModel(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun process(text: String): MonoResult {
        val call = runCatching { client.chat(model, SYSTEM, text, jsonMode = true) }
        val result = call.getOrNull()
            ?: return MonoResult.Failed("транспорт: ${call.exceptionOrNull()?.message?.take(200)}")
        val metrics = CallMetrics.of(result)
        val obj = runCatching { json.parseToJsonElement(result.content).jsonObject }.getOrNull()
            ?: return MonoResult.Failed("невалидный JSON: ${result.content.take(200)}", metrics)
        val decision = Decision.parse(runCatching { obj["decision"]?.jsonPrimitive?.contentOrNull }.getOrNull())
            ?: return MonoResult.Failed("decision вне enum: ${result.content.take(200)}", metrics)
        val message = runCatching { obj["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: return MonoResult.Failed("пустой message: ${result.content.take(200)}", metrics)
        val fields = obj["fields"]?.let { ClaimFields.fromRaw(it.toString()) }
        return MonoResult.Done(decision, message, fields, result.content, metrics)
    }

    companion object {
        /** Всё в одном промпте — ровно то, от чего день 9 уходит в варианте B. */
        val SYSTEM = """
            Ты — обработчик претензий клиентов мобильного банка (возвраты и спорные списания).
            За ОДИН ответ сделай всё сразу:
            1) Извлеки из зашумлённого текста клиента поля:
               amt — сумма спорного списания (число или null),
               cur — валюта "RUB"|"USD"|"EUR"|null («руб», «рэ», «рублей» → RUB),
               mrc — название продавца/магазина или null,
               dt — дата операции "YYYY-MM-DD" или null,
               rcp — true|false, упомянут чек/квитанция/подтверждение,
               dup — true|false, клиент говорит о двойном/повторном списании,
               frd — true|false, клиент заявляет о мошенничестве или что операцию не совершал.
               Чего в тексте нет — null/false, не выдумывай.
            2) Прими решение по правилам.
            ${DecisionRules.PROMPT_RULES}
            3) Сформулируй 1-2 вежливых предложения клиенту по-русски: что решено и что
               будет дальше, без внутренних кодов правил.
            Верни строго JSON: {"decision": "AUTO_REFUND"|"MANUAL_REVIEW"|"REJECT",
            "message": "<текст клиенту>",
            "fields": {"amt":…, "cur":…, "mrc":…, "dt":…, "rcp":…, "dup":…, "frd":…}}.
        """.trimIndent()
    }
}
