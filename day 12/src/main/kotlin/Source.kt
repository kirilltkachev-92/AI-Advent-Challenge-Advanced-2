/**
 * Источник данных, который агент «подтягивает» перед ответом: письмо, документ,
 * веб-страница, файл репозитория.
 *
 * У отравленного источника два тела с ОДНИМИ И ТЕМИ ЖЕ техниками сокрытия и
 * разным стилем закладки (`rawCovert` / `rawOvert`) — это вторая ось
 * эксперимента, см. `PayloadStyle`. У чистого источника тела совпадают.
 *
 * `trusted` — не про «мы верим содержимому», а про происхождение: почтовый ящик
 * с письмами от внешних отправителей, загруженный документ и код из репозитория
 * недоверенные по определению, потому что их содержимое пишет кто угодно.
 * От этого флага зависят и метка границы (эшелон 2), и список доменов, которые
 * эшелон 3 согласен пропустить в ответ.
 */
data class Source(
    val id: String,
    val title: String,
    /** Происхождение для метки границы: `inbox`, `upload`, `web`, `repo`. */
    val origin: String,
    val trusted: Boolean,
    val rawCovert: String,
    /** По умолчанию совпадает с covert: у чистых источников стиля закладки нет. */
    val rawOvert: String = rawCovert,
    /** Чем именно отравлен источник; null — источник чистый. */
    val poisonedWith: List<String>? = null,
) {
    fun raw(style: PayloadStyle): String = when (style) {
        PayloadStyle.COVERT -> rawCovert
        PayloadStyle.OVERT -> rawOvert
    }

    val trustLabel: String get() = if (trusted) "trusted" else "untrusted"
    val poisoned: Boolean get() = poisonedWith != null
}
