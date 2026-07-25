import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * parseDotEnvLine — чистый парсер строки .env: без файлов и env,
 * только строка на входе → пара (или null) на выходе.
 */
class ConfigTest {

    // ── строки, которые игнорируются ──

    @Test
    fun `пустая и пробельная строки дают null`() {
        assertNull(parseDotEnvLine(""))
        assertNull(parseDotEnvLine("   \t  "))
    }

    @Test
    fun `комментарий даёт null, включая комментарий с отступом`() {
        assertNull(parseDotEnvLine("# DEEPSEEK_API_KEY=sk-real"))
        assertNull(parseDotEnvLine("   # отступ перед комментарием"))
    }

    @Test
    fun `строка без знака равно даёт null`() {
        assertNull(parseDotEnvLine("JUST_A_KEY"))
    }

    @Test
    fun `строка без ключа перед равно даёт null`() {
        assertNull(parseDotEnvLine("=value"))
    }

    // ── обычные пары ──

    @Test
    fun `простая пара KEY=VALUE парсится`() {
        assertEquals("PORT" to "8080", parseDotEnvLine("PORT=8080"))
    }

    @Test
    fun `пробелы вокруг ключа и значения обрезаются`() {
        assertEquals("PORT" to "8080", parseDotEnvLine("  PORT  =  8080  "))
    }

    @Test
    fun `пустое значение допустимо`() {
        assertEquals("EMPTY" to "", parseDotEnvLine("EMPTY="))
    }

    @Test
    fun `равно внутри значения не режет его`() {
        assertEquals(
            "URL" to "https://x?a=1&b=2",
            parseDotEnvLine("URL=https://x?a=1&b=2"),
        )
    }

    // ── кавычки ──

    @Test
    fun `двойные кавычки вокруг значения снимаются`() {
        assertEquals("NAME" to "hello world", parseDotEnvLine("NAME=\"hello world\""))
    }

    @Test
    fun `одинарные кавычки вокруг значения снимаются`() {
        assertEquals("NAME" to "hello world", parseDotEnvLine("NAME='hello world'"))
    }

    @Test
    fun `кавычки снимаются после обрезки пробелов`() {
        assertEquals("KEY" to " value ", parseDotEnvLine("KEY =  \" value \"  "))
    }

    @Test
    fun `непарная кавычка остаётся в значении`() {
        assertEquals("A" to "\"open", parseDotEnvLine("A=\"open"))
        assertEquals("B" to "'x\"", parseDotEnvLine("B='x\""))
    }

    @Test
    fun `кавычки внутри значения не трогаются`() {
        assertEquals("MSG" to "say \"hi\" now", parseDotEnvLine("MSG=say \"hi\" now"))
    }

    @Test
    fun `одиночная кавычка как всё значение остаётся как есть`() {
        assertEquals("Q" to "\"", parseDotEnvLine("Q=\""))
    }
}
