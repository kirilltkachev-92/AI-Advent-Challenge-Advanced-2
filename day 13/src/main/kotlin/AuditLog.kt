import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime

/**
 * Одна запись аудита. Инвариант: сырые секреты сюда не попадают никогда —
 * во Finding уже лежит замаскированный fragment (`sk-pr…c123`).
 */
@Serializable
data class AuditEntry(
    val ts: String = OffsetDateTime.now().toString(),
    @SerialName("client_ip") val clientIp: String,
    val mode: String,
    val status: String,
    @SerialName("input_action") val inputAction: String,
    @SerialName("input_findings") val inputFindings: List<Finding> = emptyList(),
    @SerialName("output_action") val outputAction: String? = null,
    @SerialName("output_findings") val outputFindings: List<Finding> = emptyList(),
    @SerialName("output_warnings") val outputWarnings: List<String> = emptyList(),
    @SerialName("prompt_len") val promptLen: Int,
    @SerialName("answer_len") val answerLen: Int? = null,
    val model: String? = null,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
    @SerialName("latency_ms") val latencyMs: Long,
)

/**
 * Журнал аудита: источник истины — файл output/audit.jsonl (JSON line на запись),
 * плюс кольцевое зеркало последних записей в памяти для GET /v1/audit.
 */
class AuditLog(
    private val path: Path = Path.of("output/audit.jsonl"),
    private val keepLast: Int = 200,
) {
    private val json = Json { encodeDefaults = true }
    private val recent = ArrayDeque<AuditEntry>()

    @Synchronized
    fun append(entry: AuditEntry) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            json.encodeToString(entry) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        recent.addLast(entry)
        while (recent.size > keepLast) recent.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): List<AuditEntry> = recent.takeLast(n.coerceIn(1, keepLast))
}
