import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Config.parseDotEnvLine: чистый парсер строки `.env` — комментарии, пустые строки,
 * строки без '=', кавычки, пробелы. Реальный `.env` не читается: функция чистая.
 */
class ConfigTest {

    @Test
    fun `обычная пара KEY=value`() {
        assertEquals("KEY" to "value", Config.parseDotEnvLine("KEY=value"))
    }

    @Test
    fun `пустая строка и строка из пробелов игнорируются`() {
        assertNull(Config.parseDotEnvLine(""))
        assertNull(Config.parseDotEnvLine("   \t  "))
    }

    @Test
    fun `комментарий игнорируется, в том числе с отступом`() {
        assertNull(Config.parseDotEnvLine("# comment"))
        assertNull(Config.parseDotEnvLine("   # KEY=value внутри комментария"))
    }

    @Test
    fun `строка без знака равенства игнорируется`() {
        assertNull(Config.parseDotEnvLine("JUST_A_WORD"))
    }

    @Test
    fun `строка с пустым ключом игнорируется`() {
        assertNull(Config.parseDotEnvLine("=value"))
        assertNull(Config.parseDotEnvLine("   =value"))
    }

    @Test
    fun `пробелы вокруг ключа и значения обрезаются`() {
        assertEquals("KEY" to "value", Config.parseDotEnvLine("  KEY  =  value  "))
    }

    @Test
    fun `двойные кавычки вокруг значения снимаются, пробелы внутри сохраняются`() {
        assertEquals("KEY" to "a b c", Config.parseDotEnvLine("KEY=\"a b c\""))
    }

    @Test
    fun `одинарные кавычки вокруг значения снимаются`() {
        assertEquals("KEY" to "a b", Config.parseDotEnvLine("KEY='a b'"))
    }

    @Test
    fun `непарные кавычки не трогаются`() {
        assertEquals("KEY" to "\"unclosed", Config.parseDotEnvLine("KEY=\"unclosed"))
        assertEquals("KEY" to "'mixed\"", Config.parseDotEnvLine("KEY='mixed\""))
    }

    @Test
    fun `пустое значение допустимо`() {
        assertEquals("KEY" to "", Config.parseDotEnvLine("KEY="))
        assertEquals("KEY" to "", Config.parseDotEnvLine("KEY=\"\""))
    }

    @Test
    fun `второй знак равенства уходит в значение`() {
        assertEquals("KEY" to "a=b", Config.parseDotEnvLine("KEY=a=b"))
    }

    @Test
    fun `значение из одного символа кавычки не ломает парсер`() {
        assertEquals("KEY" to "\"", Config.parseDotEnvLine("KEY=\""))
    }
}
