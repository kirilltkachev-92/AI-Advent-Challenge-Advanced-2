import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ядро дня 8 — роутинг обращения между двумя ярусами моделей.
 * Контракт: текст обращения → RouteResult.
 * Стратегия: сначала дешёвый flash; эскалация на сильный pro, если сработала
 * ЛЮБАЯ из именованных эвристик (имя попадает в отчёт):
 *  1) низкая уверенность — self-reported confidence < 0.75;
 *  2) невалидный ответ — JSON не парсится, категория вне белого списка,
 *     confidence вне [0;1] (оборонительный парсинг);
 *  3) «не уверен — эскалируй» — категория «другое» при confidence < 0.9:
 *     сброс в корзину-свалку — сигнал, что дешёвая модель не поняла запрос;
 *  4) транспорт — HTTP-ошибка дешёвого яруса (не считаем это ответом).
 * Fallback: ответ pro принимается даже с низкой уверенностью — это последний
 * ярус; но если и pro вернул невалидный JSON/категорию — Failed без третьей модели.
 */
class ModelRouter(
    private val client: DeepSeekClient = DeepSeekClient(),
    private val cheapModel: String = Config.cheapModel(),
    private val strongModel: String = Config.strongModel(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun route(text: String): RouteResult {
        val cheapCall = runCatching { client.chat(cheapModel, SYSTEM, text, jsonMode = true, temperature = 0.0) }
        val cheap = cheapCall.getOrNull()
            ?: return escalate(
                text,
                // текст ошибки дешёвого яруса сохраняем в имени эвристики — иначе он теряется для отчёта
                "$HEURISTIC_TRANSPORT: ${cheapCall.exceptionOrNull()?.message?.take(120)}",
                cheapAttempt = null,
                cheapMetrics = CallMetrics.ZERO,
            )
        val cheapMetrics = CallMetrics.of(cheap)
        val attempt = parse(cheap.content)
        val fired = firedHeuristic(attempt)
            ?: return RouteResult.Routed(
                answer = checkNotNull(attempt),
                tier = Tier.CHEAP,
                escalated = false,
                firedHeuristic = null,
                cheapAttempt = null,
                cheapMetrics = cheapMetrics,
                strongMetrics = CallMetrics.ZERO,
            )
        return escalate(text, fired, attempt, cheapMetrics)
    }

    // ── эвристики эскалации: каждая — именованная проверка ──────────────────

    /** null — эвристики молчат, ответ дешёвой модели принимается. */
    private fun firedHeuristic(attempt: Triage?): String? = when {
        attempt == null -> HEURISTIC_INVALID
        isLowConfidence(attempt) -> HEURISTIC_LOW_CONFIDENCE
        isUnsureCatchAll(attempt) -> HEURISTIC_CATCH_ALL
        else -> null
    }

    private fun isLowConfidence(t: Triage): Boolean = t.confidence < LOW_CONFIDENCE_THRESHOLD

    private fun isUnsureCatchAll(t: Triage): Boolean =
        t.category == "другое" && t.confidence < CATCH_ALL_THRESHOLD

    // ── fallback: сильный ярус ──────────────────────────────────────────────

    private fun escalate(text: String, heuristic: String, cheapAttempt: Triage?, cheapMetrics: CallMetrics): RouteResult {
        val strongCall = runCatching { client.chat(strongModel, SYSTEM, text, jsonMode = true, temperature = 0.0) }
        val strong = strongCall.getOrNull()
            ?: return RouteResult.Failed(
                reason = "сильный ярус недоступен: ${strongCall.exceptionOrNull()?.message?.take(200)}",
                firedHeuristic = heuristic,
                cheapMetrics = cheapMetrics,
                strongMetrics = CallMetrics.ZERO,
            )
        val strongMetrics = CallMetrics.of(strong)
        val answer = parse(strong.content)
            ?: return RouteResult.Failed(
                reason = "сильный ярус вернул невалидный ответ: ${strong.content.take(200)}",
                firedHeuristic = heuristic,
                cheapMetrics = cheapMetrics,
                strongMetrics = strongMetrics,
            )
        return RouteResult.Routed(
            answer = answer,
            tier = Tier.STRONG,
            escalated = true,
            firedHeuristic = heuristic,
            cheapAttempt = cheapAttempt,
            cheapMetrics = cheapMetrics,
            strongMetrics = strongMetrics,
        )
    }

    // ── оборонительный парсинг: любой дефект → null (невалидный ответ) ──────

    private fun parse(raw: String): Triage? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val category = runCatching { obj["category"]?.jsonPrimitive?.content }.getOrNull()?.trim() ?: return null
        if (category !in Triage.CATEGORIES) return null
        val confidence = runCatching { obj["confidence"]?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: return null
        if (confidence !in 0.0..1.0) return null
        val reason = runCatching { obj["reason"]?.jsonPrimitive?.content }.getOrNull().orEmpty().ifBlank { "без причины" }
        return Triage(category, confidence, reason)
    }

    companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 0.75
        const val CATCH_ALL_THRESHOLD = 0.9

        // Имена эвристик — попадают в прогресс-строки и отчёт как есть.
        const val HEURISTIC_LOW_CONFIDENCE = "confidence<0.75"
        const val HEURISTIC_INVALID = "невалидный формат"
        const val HEURISTIC_CATCH_ALL = "другое+conf<0.9"
        const val HEURISTIC_TRANSPORT = "транспорт flash"

        val SYSTEM = """
            Ты — маршрутизатор обращений в поддержку мобильного банка.
            Отнеси сообщение пользователя ровно к одной категории:
            карта_заблокирована | платёж_не_прошёл | кредит_вопрос | мошенничество | приложение_баг | другое.
            Верни строго JSON: {"category": "<категория из списка>",
            "confidence": число от 0.0 до 1.0 — насколько ты уверен в категории,
            "reason": "одно предложение по-русски"}.
            Если сообщение не про банк, смешивает несколько тем или трактовка
            неоднозначна — выбирай «другое» и честно занижай confidence.
        """.trimIndent()
    }
}
