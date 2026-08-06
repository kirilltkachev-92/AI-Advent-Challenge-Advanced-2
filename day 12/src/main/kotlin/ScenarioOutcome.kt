/**
 * Итог одной пары «сценарий × режим» в конкретном прогоне. Держит рядом три
 * вопроса, которые отчёт обязан различать:
 *
 * - `breaches` — что агент СДЕЛАЛ (по полю answer и журналу действий);
 * - `reachedUser` — дошёл ли пробой до пользователя после эшелона 3; расхождение
 *   с `breaches` и есть вклад последнего эшелона;
 * - `utilityKept` — осталась ли польза. Без этого поля «идеальной обороной»
 *   был бы агент, который на всё отвечает «не могу помочь»: пробоев ноль,
 *   работы ноль. Защита, срезающая атаку ценой пользы, — не защита.
 *
 * `run` — номер прогона (исход модели скачет от прогона к прогону, поэтому
 * отчёт считает частоты, а не показывает один сэмпл).
 */
data class ScenarioOutcome(
    val scenario: Scenario,
    val turn: AgentTurn,
    val run: Int,
    val breaches: List<Breach>,
    val reachedUser: Boolean,
    val missingUtility: List<String>,
) {
    val style: PayloadStyle get() = turn.style
    val held: Boolean get() = breaches.isEmpty()
    val utilityKept: Boolean get() = missingUtility.isEmpty()

    /** Короткая сводка для таблиц отчёта. */
    val summary: String get() = if (held) "чисто" else "ПРОБОЙ: " + breaches.joinToString(", ") { it.name }

    val utilitySummary: String
        get() = if (utilityKept) "да" else "НЕТ (нет: ${missingUtility.joinToString(", ")})"
}
