import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Motivator: доменная логика поверх ChatClient-фейка — что уходит в LLM
 * (системный промпт, задача, повышенная temperature) и как чистится ответ
 * (trim + снятие обрамляющих кавычек).
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
    fun `ответ возвращается как есть, если он уже чистый`() {
        val client = RecordingClient("Каждый тест — шаг к надёжному релизу.")
        assertEquals(
            "Каждый тест — шаг к надёжному релизу.",
            Motivator(client).motivate("покрыть сервис тестами"),
        )
    }

    @Test
    fun `обрезает пробелы и переводы строк вокруг ответа`() {
        val client = RecordingClient("\n  Вперёд к цели!  \n")
        assertEquals("Вперёд к цели!", Motivator(client).motivate("задача"))
    }

    @Test
    fun `снимает обрамляющие кавычки после trim`() {
        val client = RecordingClient(" \"Ты справишься с этим багом!\" ")
        assertEquals("Ты справишься с этим багом!", Motivator(client).motivate("задача"))
    }

    @Test
    fun `кавычки внутри фразы не трогает`() {
        val client = RecordingClient("Скажи багу \"прощай\" сегодня")
        assertEquals("Скажи багу \"прощай\" сегодня", Motivator(client).motivate("задача"))
    }
}
