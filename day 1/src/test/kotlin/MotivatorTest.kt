import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Motivator: доменная логика поверх ChatClient-фейка — что уходит в LLM
 * (системный промпт, задача, повышенная temperature), как чистится ответ
 * (схлопывание пробелов/переводов строк, trim, снятие обрамляющих кавычек —
 * прямых и «ёлочек») и как исключения транспорта превращаются
 * в MotivationResult.Failed.
 */
class MotivatorTest {

    /** Фейк, запоминающий аргументы вызова и отдающий заготовленный ответ. */
    private class RecordingClient(private val reply: String) : ChatClient {
        var system: String? = null
        var user: String? = null
        var temperature: Double? = null

        override fun chat(system: String, user: String, temperature: Double): String {
            this.system = system
            this.user = user
            this.temperature = temperature
            return reply
        }
    }

    /** Успешный вызов обязан вернуть Done — иначе тест падает с понятным сообщением. */
    private fun phraseOf(result: MotivationResult): String =
        assertIs<MotivationResult.Done>(result, "ожидался Done").phrase

    @Test
    fun `передаёт задачу как user-сообщение с temperature 1_1`() {
        val client = RecordingClient("Дожми этот рефакторинг!")
        Motivator(client).motivate("рефакторинг легаси-модуля")

        assertEquals("рефакторинг легаси-модуля", client.user)
        assertEquals(1.1, client.temperature)
    }

    @Test
    fun `системный промпт задаёт роль мотиватора и ограничение формата`() {
        val client = RecordingClient("ответ")
        Motivator(client).motivate("задача")

        val system = client.system.orEmpty()
        assertTrue("мотиватор" in system, "промпт должен задавать роль мотиватора")
        assertTrue("200 символов" in system, "промпт должен ограничивать длину")
    }

    @Test
    fun `ответ возвращается как есть, если он уже чистый`() {
        val client = RecordingClient("Каждый тест — шаг к надёжному релизу.")
        assertEquals(
            "Каждый тест — шаг к надёжному релизу.",
            phraseOf(Motivator(client).motivate("покрыть сервис тестами")),
        )
    }

    @Test
    fun `обрезает пробелы и переводы строк вокруг ответа`() {
        val client = RecordingClient("\n  Вперёд к цели!  \n")
        assertEquals("Вперёд к цели!", phraseOf(Motivator(client).motivate("задача")))
    }

    @Test
    fun `снимает обрамляющие кавычки после trim`() {
        val client = RecordingClient(" \"Ты справишься с этим багом!\" ")
        assertEquals("Ты справишься с этим багом!", phraseOf(Motivator(client).motivate("задача")))
    }

    @Test
    fun `снимает обрамляющие ёлочки`() {
        val client = RecordingClient("«Каждый коммит приближает релиз!»")
        assertEquals("Каждый коммит приближает релиз!", phraseOf(Motivator(client).motivate("задача")))
    }

    @Test
    fun `снимает ёлочки после trim`() {
        val client = RecordingClient("  «Дожми этот модуль!»\n")
        assertEquals("Дожми этот модуль!", phraseOf(Motivator(client).motivate("задача")))
    }

    @Test
    fun `схлопывает множественные пробелы и переводы строк в один пробел`() {
        val client = RecordingClient("Первый шаг\n\nсделан —   остальное\tдожмёшь!")
        assertEquals(
            "Первый шаг сделан — остальное дожмёшь!",
            phraseOf(Motivator(client).motivate("задача")),
        )
    }

    @Test
    fun `ёлочки внутри фразы не трогает`() {
        val client = RecordingClient("Скажи «готово» уже сегодня")
        assertEquals("Скажи «готово» уже сегодня", phraseOf(Motivator(client).motivate("задача")))
    }

    @Test
    fun `кавычки внутри фразы не трогает`() {
        val client = RecordingClient("Скажи багу \"прощай\" сегодня")
        assertEquals("Скажи багу \"прощай\" сегодня", phraseOf(Motivator(client).motivate("задача")))
    }

    // ── исключения транспорта → Failed ──

    @Test
    fun `исключение клиента превращается в Failed с его сообщением`() {
        val client = ChatClient { _, _, _ -> error("DeepSeek → HTTP 500: boom") }
        val result = Motivator(client).motivate("задача")

        assertEquals(MotivationResult.Failed("DeepSeek → HTTP 500: boom"), result)
    }

    @Test
    fun `исключение без сообщения превращается в Failed с дефолтной причиной`() {
        val client = ChatClient { _, _, _ -> throw RuntimeException() }
        val result = Motivator(client).motivate("задача")

        assertEquals(MotivationResult.Failed("DeepSeek недоступен"), result)
    }
}
