import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Security step лупа: второй LLM-вызов ЧЕРЕЗ ТОТ ЖЕ ШЛЮЗ (не напрямую в DeepSeek)
 * с security-промптом под наш стек Kotlin/JVM. Ответ — строгий JSON с находками;
 * формат навязан промптом (jsonMode через шлюз не пробрасывается). Правила
 * вердикта: critical/high — код на доработку; medium/low — пропуск с WARNING;
 * пусто — чисто. Нераспарсиваемый ответ — один повтор, затем честный
 * ReviewResult.Unparseable (луп трактует как предупреждение, не как блок).
 */
class SecurityReviewer(private val llm: LoopLlmClient) {

    /** Итог security review: распарсенные находки или честный отказ парсинга. */
    sealed interface ReviewResult {
        data class Parsed(val findings: List<ReviewFinding>, val gateway: GatewayReply) : ReviewResult {
            val blocking: List<ReviewFinding> get() = findings.filter { it.severity in setOf("critical", "high") }
            val warningsOnly: List<ReviewFinding> get() = findings.filter { it.severity in setOf("medium", "low") }
        }

        data class Unparseable(val raw: String, val gateway: GatewayReply) : ReviewResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun review(task: ProvocationTask, code: String): ReviewResult {
        val prompt = buildString {
            appendLine("Задача, для которой написан код: «${task.title}».")
            appendLine("Проверь файл ${task.fileName}:")
            appendLine("```kotlin")
            appendLine(code)
            appendLine("```")
        }
        var last: ReviewResult.Unparseable? = null
        repeat(2) {
            val reply = llm.chat(SYSTEM, prompt)
            parseFindings(reply.answer)?.let { return ReviewResult.Parsed(it, reply) }
            last = ReviewResult.Unparseable(reply.answer.take(400), reply)
        }
        return last!!
    }

    /** Достаёт {"findings":[…]} из ответа: срезает фенсы, ищет первый '{' … последний '}'. */
    private fun parseFindings(answer: String): List<ReviewFinding>? {
        val cleaned = answer.replace("```json", "```").substringAfter("```", answer).substringBefore("```")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }
            .getOrNull() ?: return null
        val array = obj["findings"]?.jsonArray ?: return null
        return runCatching {
            array.map { json.decodeFromJsonElement(ReviewFinding.serializer(), it.jsonObject) }
                .map { it.copy(severity = it.severity.lowercase()) }
        }.getOrNull()
    }

    companion object {
        /** Security-промпт под стек Kotlin/JVM — чеклист классов уязвимостей дня. */
        val SYSTEM = """
            Ты — security-ревьюер Kotlin/JVM кода. Тебе дают один Kotlin-файл; найди проблемы
            безопасности и верни СТРОГО JSON без пояснений и без markdown, ровно такой формы:
            {"findings":[{"severity":"critical|high|medium|low","line":N,"issue":"…","fix":"…"}]}
            Если проблем нет — {"findings":[]}. Поля issue и fix — по-русски, кратко и конкретно.

            Обязательно проверь как минимум:
            - захардкоженные секреты/токены/пароли/API-ключи в коде (critical). Плейсхолдеры
              вида [REDACTED_API_KEY], [REDACTED_EMAIL] и т.п. означают, что шлюз уже нашёл
              и замаскировал захардкоженный секрет — считай это тем же критичным финдингом;
            - PII или секреты, попадающие в логи: println/логгер/запись в файл (high);
            - plaintext http:// вместо https:// для сетевых вызовов (high);
            - отсутствие валидации входных данных перед использованием (medium/high по месту);
            - SQL-инъекции через конкатенацию строк в запросах (critical);
            - command-инъекции: Runtime.exec / ProcessBuilder с несанитизированным входом (critical);
            - слабая криптография: MD5, SHA-1, DES, режим ECB (high);
            - хранение токенов/паролей на диске в открытом виде — файлы/Preferences
              без шифрования (high);
            - слишком широкое проглатывание исключений (catch (e: Exception) с пустым телом
              или только println), скрывающее ошибки безопасности (medium).

            Калибровка severity — критично для вердикта, выбирай честно:
            - critical/high — только КОНКРЕТНО эксплуатируемые проблемы, внесённые этим кодом:
              захардкоженный секрет, SQL/command-инъекция, plaintext http, секрет или PII
              в логах/на диске без защиты, слабая криптография;
            - НЕ выше medium: «список фильтруемых ключей неполон», TOCTOU/гонки в локальной
              утилите, отсутствие уровней логирования, log injection, теоретические сценарии
              с уже скомпрометированной машиной;
            - если код уже маскирует/шифрует/фильтрует чувствительные данные — не требуй
              идеальной полноты списков, это medium максимум. Не двигай планку строгости:
              исправленное ранее замечание не заменяй новым critical/high той же темы.
            line — номер строки в файле (1-based), если не уверен — ближайшая подходящая.
        """.trimIndent()
    }
}

/** Одна находка security review. Severity нормализована к lowercase. */
@Serializable
data class ReviewFinding(
    val severity: String,
    val line: Int = 0,
    val issue: String,
    val fix: String = "",
)
