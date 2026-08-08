/** Действие выходного стража над ответом модели. */
enum class OutputAction { PASS, SANITIZE, FLAG }

/**
 * Итог проверки ответа: `answer` уже безопасен для выдачи (секреты замаскированы),
 * `warnings` — список причин, почему ответ помечен как подозрительный.
 */
data class OutputVerdict(
    val action: OutputAction,
    val answer: String,
    val findings: List<Finding>,
    val warnings: List<String>,
)

/**
 * Выходной страж: проверяет ответ модели ДО возврата пользователю.
 * Политика: сгенерированные секреты — маскируются (SANITIZE); утечка системного
 * промпта, подозрительные URL и опасные команды — ответ отдаётся, но с
 * предупреждениями (FLAG). SANITIZE имеет приоритет над FLAG в поле action,
 * предупреждения при этом сохраняются.
 */
class OutputGuard(
    private val scanner: SecretScanner = SecretScanner(),
    private val systemPrompt: String = GatewayPrompt.SYSTEM,
) {

    private val leakPhrases = listOf(
        "мой системный промпт", "системный промпт", "системного промпта",
        "мои инструкции", "my system prompt", "my instructions",
    )
    private val urlRegex = Regex("""https?://[^\s"'<>()\[\]]+""")
    private val rawIpHost = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val shorteners = setOf("bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "cutt.ly", "clck.ru")
    private val brandWords = listOf("paypal", "google", "apple", "sberbank", "microsoft", "github", "amazon")
    private val dangerousCommands = listOf(
        Regex("""rm\s+-rf?\b""") to "деструктивная команда rm -rf",
        Regex("""curl[^|\n]*\|\s*(?:ba|z)?sh""") to "пайп curl … | sh — выполнение скрипта из сети",
        Regex("""wget[^|\n]*\|\s*(?:ba|z)?sh""") to "пайп wget … | sh — выполнение скрипта из сети",
        Regex("""powershell[^\n]*\s-enc\w*\s""", RegexOption.IGNORE_CASE) to "powershell -enc — обфусцированная команда",
        Regex("""chmod\s+777\b""") to "chmod 777 — открытие прав всем",
        Regex("""\bmkfs\b""") to "mkfs — форматирование диска",
        Regex("""\bdd\s+if=""") to "dd if=… — низкоуровневая запись на диск",
        Regex(""":\(\)\s*\{\s*:\|:""") to "fork-бомба",
    )

    fun inspect(answer: String): OutputVerdict {
        val findings = scanner.scan(answer)
        val warnings = mutableListOf<String>()
        warnings += leakWarnings(answer)
        warnings += urlWarnings(answer)
        warnings += commandWarnings(answer)

        val safeAnswer = if (findings.isEmpty()) answer else scanner.maskAll(answer, findings)
        val action = when {
            findings.isNotEmpty() -> OutputAction.SANITIZE
            warnings.isNotEmpty() -> OutputAction.FLAG
            else -> OutputAction.PASS
        }
        return OutputVerdict(action, safeAnswer, findings, warnings)
    }

    // ── Утечка системного промпта ────────────────────────────────────────

    private fun leakWarnings(answer: String): List<String> {
        val lower = answer.lowercase()
        val warnings = mutableListOf<String>()
        leakPhrases.firstOrNull { it in lower }?.let {
            warnings += "возможная утечка системного промпта: фраза «$it»"
        }
        // Дословное совпадение с длинной строкой промпта или служебной меткой.
        val leakedLine = systemPrompt.lines().map(String::trim).filter { it.length >= 25 }.any { it in answer }
        if (leakedLine || "GW-14-ORION" in answer) {
            warnings += "утечка системного промпта: дословное совпадение с текстом инструкций"
        }
        return warnings
    }

    // ── Подозрительные URL ───────────────────────────────────────────────

    private fun urlWarnings(answer: String): List<String> {
        val warnings = mutableListOf<String>()
        urlRegex.findAll(answer).forEach { m ->
            val url = m.value.trimEnd('.', ',', ';', ':')
            val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
            when {
                rawIpHost.matches(host) -> warnings += "URL с голым IP-адресом: $url"
                host.split(".").any { it.startsWith("xn--") } -> warnings += "punycode-домен (возможный lookalike): $url"
                host in shorteners -> warnings += "сокращатель ссылок скрывает адресата: $url"
                url.startsWith("http://") && brandWords.any { it in host } ->
                    warnings += "не-HTTPS ссылка на известный бренд: $url"
            }
        }
        return warnings
    }

    // ── Опасные команды ──────────────────────────────────────────────────

    private fun commandWarnings(answer: String): List<String> =
        dangerousCommands.filter { (regex, _) -> regex.containsMatchIn(answer) }
            .map { (_, label) -> "опасная команда в ответе: $label" }
}
