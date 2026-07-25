import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Motivator: доменная логика поверх ChatClient-фейка — что уходит в LLM
 * (системный промпт, задача, повышенная temperature), как чистится ответ
 * (схлопывание whitespace + снятие обрамляющих кавычек "…" и «…»)
 * и как транспортные исключения превращаются в MotivationResult.Failed
 * вместо проброса наружу.
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
    fun `ответ возвращается как Done, если он уже чистый`() {
        val client = RecordingClient("Каждый тест — шаг к надёжному релизу.")
        assertEquals(
            MotivationResult.Done("Каждый тест — шаг к надёжному релизу."),
            Motivator(client).motivate("покрыть сервис тестами"),
        )
    }

    @Test
    fun `обрезает пробелы и переводы строк вокруг ответа`() {
        val client = RecordingClient("\n  Вперёд к цели!  \n")
        assertEquals(MotivationResult.Done("Вперёд к цели!"), Motivator(client).motivate("задача"))
    }

    @Test
    fun `снимает обрамляющие кавычки после trim`() {
        val client = RecordingClient(" \"Ты справишься с этим багом!\" ")
        assertEquals(MotivationResult.Done("Ты справишься с этим багом!"), Motivator(client).motivate("задача"))
    }

    @Test
    fun `кавычки внутри фразы не трогает`() {
        val client = RecordingClient("Скажи багу \"прощай\" сегодня")
        assertEquals(MotivationResult.Done("Скажи багу \"прощай\" сегодня"), Motivator(client).motivate("задача"))
    }

    @Test
    fun `снимает обрамляющие ёлочки`() {
        val client = RecordingClient(" «Дожми этот сервис до релиза!» ")
        assertEquals(MotivationResult.Done("Дожми этот сервис до релиза!"), Motivator(client).motivate("задача"))
    }

    @Test
    fun `ёлочки внутри фразы не трогает`() {
        val client = RecordingClient("Скажи «поехали» и запусти деплой")
        assertEquals(MotivationResult.Done("Скажи «поехали» и запусти деплой"), Motivator(client).motivate("задача"))
    }

    @Test
    fun `схлопывает множественные пробелы и переводы строк в один пробел`() {
        val client = RecordingClient("Вперёд,\n\n  к   цели\t— без   пауз!")
        assertEquals(MotivationResult.Done("Вперёд, к цели — без пауз!"), Motivator(client).motivate("задача"))
    }

    @Test
    fun `чистит комбинацию — кавычки, переводы строк и лишние пробелы разом`() {
        val client = RecordingClient("\n \"Каждый  коммит\nприближает   релиз!\" \n")
        assertEquals(MotivationResult.Done("Каждый коммит приближает релиз!"), Motivator(client).motivate("задача"))
    }

    // ── транспортные ошибки → Failed, не исключение ──

    @Test
    fun `падение клиента превращается в Failed с его сообщением`() {
        val client = ChatClient { _, _, _ -> error("DeepSeek → HTTP 500: boom") }
        assertEquals(
            MotivationResult.Failed("DeepSeek → HTTP 500: boom"),
            Motivator(client).motivate("задача"),
        )
    }

    @Test
    fun `падение клиента без сообщения даёт дефолтную причину`() {
        val client = ChatClient { _, _, _ -> throw RuntimeException() }
        assertEquals(
            MotivationResult.Failed("DeepSeek недоступен"),
            Motivator(client).motivate("задача"),
        )
    }
}
