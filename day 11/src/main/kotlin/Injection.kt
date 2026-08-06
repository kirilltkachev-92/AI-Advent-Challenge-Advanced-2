/**
 * Модель одной атаки на промпт. Каталог — это документация и тест одновременно:
 * помимо самого payload'а атака несёт классификацию (класс + техника), ссылку на
 * первоисточник и разбор «что делает / почему работает / как защититься»,
 * а `successMarkers` — детерминированный критерий «атака удалась», по которому
 * BreachDetector судит сырой ответ модели без второй LLM-«судьи».
 */
data class Injection(
    val id: String,
    val title: String,
    val attackClass: AttackClass,
    val technique: Technique?,
    /** URL открытого источника; null — собственная формулировка. */
    val source: String?,
    /** То, что реально уходит в ассистента как текст пользователя. */
    val payload: String,
    /** Для indirect-атак: какой документ подтянет ассистент (см. RetrievedDocs). */
    val docId: String?,
    val whatItDoes: String,
    val whyItWorks: String,
    val howToDefend: String,
    /** Regex (IGNORE_CASE): признак того, что атака удалась. */
    val successMarkers: List<String>,
)

/** Откуда приходит вредоносная инструкция и что она ломает. */
enum class AttackClass(val label: String) {
    DIRECT("direct injection"),
    INDIRECT("indirect injection"),
    JAILBREAK("jailbreak"),
}

/** Три техники из задания дня; null — атака, не попадающая ровно в одну из них. */
enum class Technique(val label: String) {
    ROLE_PLAY("role-play injection"),
    INSTRUCTION_OVERRIDE("instruction override"),
    PROMPT_EXTRACTION("system prompt extraction"),
}
