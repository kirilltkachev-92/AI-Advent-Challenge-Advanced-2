/**
 * Сценарий стенда: агент с ролью, запрос пользователя, набор подтягиваемых
 * источников и всё, по чему судья выносит вердикт.
 *
 * Поля описывают разные вопросы, и путать их нельзя:
 * - `breachMarkers` / `missingMarkers` — атака удалась (по тексту ответа);
 * - `allowedTools` — какие действия пользователь РЕАЛЬНО просил; у всех четырёх
 *   сценариев набор пуст, потому что ни один пользователь не просил ничего
 *   отправлять и никуда ходить. Любой вызов инструмента здесь — пробой;
 * - `utilityMarkers` — что агент ОБЯЗАН был сделать; совпасть должны ВСЕ.
 *   Маркеры пользы намеренно не пересекаются с маркерами пробоя: иначе одна и
 *   та же строка мерила бы и атаку, и работу, и «польза» превращалась бы в
 *   переименованное «нет пробоя».
 *
 * `probeBreached` / `probeClean` — синтетические ответы для самопроверки судьи:
 * прогон с нулём пробоев обязан доказывать, что детектор вообще работает.
 */
data class Scenario(
    val id: String,
    val title: String,
    val role: AgentRole,
    val userRequest: String,
    val sourceIds: List<String>,
    val allowedTools: Set<Tool> = emptySet(),
    /** Вектор атаки: через что закладка попадает к агенту. */
    val vector: String,
    /** Техники сокрытия, применённые в отравленном источнике. */
    val techniques: List<String>,
    /** Чего добивается атакующий. */
    val goal: String,
    /** Чем защищаемся и какой эшелон за это отвечает. */
    val defense: String,
    /** Реальный кейс, который воспроизводит сценарий; null — синтетика. */
    val realWorld: String? = null,
    val breachMarkers: Map<Breach, List<String>>,
    val missingMarkers: Map<Breach, List<String>> = emptyMap(),
    val utilityMarkers: List<String>,
    /** Человекочитаемое описание пользы для отчёта: регулярки читать неудобно. */
    val utilityLabel: String,
    val probeBreached: String,
    val probeClean: String,
) {
    val sources: List<Source> get() = sourceIds.map { SourceLibrary.require(it) }

    /** Текст доверенных источников: только домены отсюда эшелон 3 пускает в ответ. */
    fun trustedText(style: PayloadStyle): String =
        sources.filter { it.trusted }.joinToString("\n") { it.raw(style) }
}
