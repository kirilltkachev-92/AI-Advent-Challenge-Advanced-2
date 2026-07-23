import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HistoryStore: кольцевой буфер в памяти — вытеснение старых записей,
 * порядок снимка (свежие первыми), выключение истории при capacity <= 0.
 */
class HistoryStoreTest {

    @Test
    fun `snapshot пуст у нового хранилища`() {
        assertEquals(emptyList(), HistoryStore(capacity = 5).snapshot())
    }

    @Test
    fun `add сохраняет task и phrase`() {
        val store = HistoryStore(capacity = 5)
        store.add("написать тесты", "Вперёд!")

        val entries = store.snapshot()
        assertEquals(1, entries.size)
        assertEquals("написать тесты", entries[0].task)
        assertEquals("Вперёд!", entries[0].phrase)
        assertTrue(entries[0].at.isNotBlank(), "поле at должно быть заполнено")
    }

    @Test
    fun `snapshot отдаёт свежие записи первыми`() {
        val store = HistoryStore(capacity = 5)
        store.add("первая", "п1")
        store.add("вторая", "п2")
        store.add("третья", "п3")

        assertEquals(listOf("третья", "вторая", "первая"), store.snapshot().map { it.task })
    }

    @Test
    fun `при переполнении вытесняется самая старая запись`() {
        val store = HistoryStore(capacity = 3)
        (1..5).forEach { store.add("задача $it", "фраза $it") }

        val entries = store.snapshot()
        assertEquals(3, entries.size)
        assertEquals(listOf("задача 5", "задача 4", "задача 3"), entries.map { it.task })
    }

    @Test
    fun `capacity 0 отключает историю`() {
        val store = HistoryStore(capacity = 0)
        store.add("задача", "фраза")
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `отрицательный capacity тоже отключает историю`() {
        val store = HistoryStore(capacity = -1)
        store.add("задача", "фраза")
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `clear удаляет все записи`() {
        val store = HistoryStore(capacity = 5)
        store.add("первая", "п1")
        store.add("вторая", "п2")

        store.clear()

        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `после clear история продолжает наполняться`() {
        val store = HistoryStore(capacity = 5)
        store.add("до очистки", "п1")
        store.clear()
        store.add("после очистки", "п2")

        assertEquals(listOf("после очистки"), store.snapshot().map { it.task })
    }

    @Test
    fun `clear пустого хранилища не падает`() {
        val store = HistoryStore(capacity = 5)
        store.clear()
        assertEquals(emptyList(), store.snapshot())
    }

    @Test
    fun `конкурентные add не теряют и не превышают ёмкость`() {
        val store = HistoryStore(capacity = 10)
        val threads = (1..4).map { t ->
            Thread {
                repeat(100) { i -> store.add("t$t-$i", "p") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(10, store.snapshot().size)
    }
}
