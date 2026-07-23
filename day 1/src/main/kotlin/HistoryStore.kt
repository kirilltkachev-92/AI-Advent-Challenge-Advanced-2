import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Одна запись истории: когда, что спросили, что ответил мотиватор. */
@Serializable
data class HistoryEntry(val at: String, val task: String, val phrase: String)

/**
 * История последних N запросов строго в памяти (по условию дня): кольцо на
 * ArrayDeque, при переполнении вытесняется самая старая запись. Потокобезопасно —
 * HttpServer обслуживает запросы из пула потоков.
 */
class HistoryStore(private val capacity: Int = Config.historySize()) {

    private val entries = ArrayDeque<HistoryEntry>()

    @Synchronized
    fun add(task: String, phrase: String) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            HistoryEntry(
                at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                task = task,
                phrase = phrase,
            ),
        )
    }

    /** Снимок истории: свежие записи первыми. */
    @Synchronized
    fun snapshot(): List<HistoryEntry> = entries.reversed()
}
