/**
 * Итог одной пары «атака × режим». Различает два разных вопроса:
 * `breaches` — что модель реально сделала (по сырому ответу), `leakedToUser` —
 * дошёл ли пробой до клиента после эшелона выхода. Их расхождение и есть цена
 * output guard: модель пробили, но наружу ничего не ушло.
 */
data class AttackOutcome(
    val injection: Injection,
    val turn: AssistantTurn,
    /** Пробой на уровне модели (по сырому ответу). */
    val breaches: List<Breach>,
    /** Пробой дошёл до клиента (по финальному ответу). */
    val leakedToUser: Boolean,
) {
    val held: Boolean get() = breaches.isEmpty()

    /** Короткая сводка для таблицы отчёта. */
    val summary: String
        get() = if (held) "устоял" else "ПРОБИТ: " + breaches.joinToString(", ") { it.name }
}
