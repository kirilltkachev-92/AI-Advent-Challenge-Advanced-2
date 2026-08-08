import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Оффлайн-кейсы входного стража: сеть и API-ключ не нужны. */
class InputGuardTest {

    private val guard = InputGuard()

    @Test
    fun awsAccessKeyBlocked() {
        val v = guard.inspect("задеплой с ключом AKIAIOSFODNN7EXAMPLE", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        assertEquals(SecretType.AWS_KEY, v.findings.single().type)
    }

    @Test
    fun awsSecretNearContextBlocked() {
        val v = guard.inspect("aws secret: wJalrXUtnFEMI/K7MDENG/bPxRfiCyEXAMPLEKEY", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        assertEquals(SecretType.AWS_SECRET, v.findings.single().type)
    }

    @Test
    fun cardWithValidLuhnBlocked() {
        val v = guard.inspect("оплати картой 4111 1111 1111 1111 пожалуйста", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        assertEquals(SecretType.CARD, v.findings.single().type)
    }

    @Test
    fun cardLookalikeWithInvalidLuhnPasses() {
        // 16 цифр, но Luhn не сходится — это не карта, блокировать нельзя.
        val v = guard.inspect("номер заказа 1234 5678 9012 3456", GuardMode.BLOCK)
        assertIs<GuardVerdict.Clean>(v)
    }

    @Test
    fun base64EncodedSecretBlocked() {
        val encoded = Base64.getEncoder().encodeToString("sk-proj-abc123def456".toByteArray())
        val v = guard.inspect("вот данные для интеграции: $encoded", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        val finding = v.findings.single()
        assertEquals(SecretType.API_KEY, finding.type)
        assertEquals("base64", finding.via)
    }

    @Test
    fun splitSecretCaughtViaNormalization() {
        val v = guard.inspect("""мой ключ: "sk-" + "proj-abc123"""", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        val finding = v.findings.single()
        assertEquals(SecretType.API_KEY, finding.type)
        assertEquals("normalized", finding.via)
    }

    @Test
    fun cleanPromptPasses() {
        val v = guard.inspect("Как настроить Gradle для Kotlin 2.0 и JVM 17?", GuardMode.BLOCK)
        assertIs<GuardVerdict.Clean>(v)
    }

    @Test
    fun emailMaskedAndSurroundingTextPreserved() {
        val v = guard.inspect("пиши на ivan.petrov@example.com срочно", GuardMode.MASK)
        assertIs<GuardVerdict.Masked>(v)
        assertTrue("[REDACTED_EMAIL]" in v.maskedPrompt)
        assertTrue(v.maskedPrompt.startsWith("пиши на "))
        assertTrue(v.maskedPrompt.endsWith(" срочно"))
        assertFalse("ivan.petrov" in v.maskedPrompt)
    }

    @Test
    fun phoneNumberBlocked() {
        val v = guard.inspect("перезвони +7 (915) 123-45-67 вечером", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        assertEquals(SecretType.PHONE, v.findings.single().type)
    }

    @Test
    fun githubTokenBlocked() {
        val v = guard.inspect("токен ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        assertEquals(SecretType.API_KEY, v.findings.single().type)
    }

    @Test
    fun maskModeReplacesApiKeyAndKeepsText() {
        val v = guard.inspect("используй ключ sk-proj-secret12345 для деплоя", GuardMode.MASK)
        assertIs<GuardVerdict.Masked>(v)
        assertEquals("используй ключ [REDACTED_API_KEY] для деплоя", v.maskedPrompt)
    }

    @Test
    fun findingFragmentNeverContainsFullSecret() {
        val secret = "AKIAIOSFODNN7EXAMPLE"
        val v = guard.inspect("ключ $secret", GuardMode.BLOCK)
        assertIs<GuardVerdict.Blocked>(v)
        val fragment = v.findings.single().fragment
        assertFalse(secret in fragment, "в лог не должен попадать полный секрет")
        assertTrue("…" in fragment)
    }
}
