import java.time.Instant
import java.util.ArrayDeque

/**
 * История запросов в памяти: хранит только последние `capacity` записей,
 * старые вытесняются. Потокобезопасна (HttpServer обслуживает запросы пулом),
 * переживает только жизнь процесса — по условию дня персистентность не нужна.
 */
class HistoryStore(private val capacity: Int = 10) {

    private val entries = ArrayDeque<HistoryEntry>(capacity)

    @Synchronized
    fun add(task: String, phrase: String) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(HistoryEntry(task, phrase, Instant.now().toString()))
    }

    /** Свежие записи первыми. */
    @Synchronized
    fun latest(): List<HistoryEntry> = entries.toList().asReversed()
}

data class HistoryEntry(val task: String, val phrase: String, val at: String)
