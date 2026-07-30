import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Поля претензии после этапа 1 «Нормализация» — компактный однострочный JSON
 * с короткими ключами (amt/cur/mrc/dt/rcp/dup/frd). Строгий компактный формат
 * на границе этапов — ключевая идея дня: он дешевле по токенам и проверяем кодом.
 * Парсинг оборонительный: дефект отдельного поля не роняет объект, а становится
 * null/false; null возвращается только если ответ вообще не JSON-объект.
 */
data class ClaimFields(
    val amt: Double?,      // сумма спорного списания
    val cur: String?,      // валюта: RUB | USD | EUR
    val mrc: String?,      // продавец/магазин
    val dt: String?,       // дата операции YYYY-MM-DD
    val rcp: Boolean,      // упомянут чек/подтверждение
    val dup: Boolean,      // двойное списание
    val frd: Boolean,      // клиент заявляет о мошенничестве
) {
    /** Однострочный компактный JSON — ровно то, что этап 2 получает на вход. */
    fun toCompactJson(): String = buildJsonObject {
        put("amt", amt)
        put("cur", cur)
        put("mrc", mrc)
        put("dt", dt)
        put("rcp", rcp)
        put("dup", dup)
        put("frd", frd)
    }.toString()

    companion object {
        private val CURRENCIES = setOf("RUB", "USD", "EUR")
        private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** null — сырой ответ этапа не распарсился в JSON-объект вовсе. */
        fun fromRaw(raw: String): ClaimFields? {
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            return ClaimFields(
                amt = obj.double("amt"),
                cur = obj.string("cur")?.uppercase()?.takeIf { it in CURRENCIES },
                mrc = obj.string("mrc"),
                dt = obj.string("dt")?.takeIf { DATE.matches(it) },
                rcp = obj.boolean("rcp"),
                dup = obj.boolean("dup"),
                frd = obj.boolean("frd"),
            )
        }

        private fun JsonObject.double(key: String): Double? =
            runCatching { get(key)?.jsonPrimitive?.doubleOrNull }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

        private fun JsonObject.boolean(key: String): Boolean =
            runCatching { get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
    }
}
