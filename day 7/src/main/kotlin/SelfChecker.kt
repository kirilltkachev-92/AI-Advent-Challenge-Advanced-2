import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Подход №3 — самопроверка вторым проходом LLM (jsonMode, temperature=0.0):
 * модель получает ИСХОДНЫЙ текст и извлечённый JSON и оценивает извлечение —
 * {"status": OK|UNSURE|FAIL, "confidence": 0..1, "reason": "…"}.
 * Парсинг оборонительный: неизвестный статус или confidence вне [0;1]
 * трактуются как UNSURE/0.5 — сомнительный вердикт проверки не должен
 * случайно превратиться в уверенный.
 */
class SelfChecker(private val client: DeepSeekClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun check(text: String, order: TransferOrder): SelfCheckOutcome {
        val user = "Текст поручения: «$text»\nИзвлечённый JSON: ${json.encodeToString(order)}"
        val result = client.chat(SYSTEM, user, jsonMode = true, temperature = 0.0)
        return SelfCheckOutcome(parse(result.content), CallMetrics.of(result))
    }

    private fun parse(raw: String): SelfCheck {
        val fallback = SelfCheck(SelfCheckStatus.UNSURE, 0.5, "ответ самопроверки не распознан")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return fallback
        val status = when (runCatching { obj["status"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.uppercase()) {
            "OK" -> SelfCheckStatus.OK
            "UNSURE" -> SelfCheckStatus.UNSURE
            "FAIL" -> SelfCheckStatus.FAIL
            else -> null
        }
        val confidence = runCatching { obj["confidence"]?.jsonPrimitive?.doubleOrNull }.getOrNull()
        if (status == null || confidence == null || confidence !in 0.0..1.0) return fallback
        val reason = runCatching { obj["reason"]?.jsonPrimitive?.content }.getOrNull().orEmpty().ifBlank { "без причины" }
        return SelfCheck(status, confidence, reason)
    }

    private companion object {
        val SYSTEM = """
            Ты — контролёр качества извлечения платёжных поручений.
            Тебе дан исходный текст и извлечённый из него JSON {amount, currency, recipient}.
            Проверь извлечение по тексту: сумма и валюта совпадают с текстом, получатель верный,
            поручение действующее (не вопрос, не отмена, не болтовня), нет второй трактовки.
            OK — извлечение точно верно; UNSURE — есть сомнение или неоднозначность; FAIL — извлечение ошибочно.
            Верни строго JSON: {"status": "OK"|"UNSURE"|"FAIL", "confidence": число от 0.0 до 1.0,
            "reason": "одно предложение по-русски"}.
        """.trimIndent()
    }
}

/** Вердикт самопроверки после оборонительного парсинга. */
data class SelfCheck(val status: SelfCheckStatus, val confidence: Double, val reason: String)

enum class SelfCheckStatus { OK, UNSURE, FAIL }

/** Вердикт + метрики вызова проверки. */
data class SelfCheckOutcome(val check: SelfCheck, val metrics: CallMetrics)
