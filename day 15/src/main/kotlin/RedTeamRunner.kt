import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText

/** Разобранный ответ /v1/execute — вход для BreachDetector. */
data class ExecuteResponse(
    val outcome: String,
    val answer: String,
    val committedCode: String?,
    val commit: String?,
    val inputMaskedTypes: List<String>,
    val outputAction: String,
    val outputWarnings: List<String>,
    val raw: String,
)

/** Итог одной атаки — сериализуется в output/runs-<phase>.json для diff «до/после». */
@Serializable
data class AttackResult(
    val id: String,
    val front: String,
    val title: String,
    val technique: String,
    val goal: String,
    val kind: String,
    /** LEAK — реальный пробой; GAP — секрет дошёл до модели (дыра в контроле); HELD — закрыто. */
    val level: String,
    val breached: Boolean,
    val outcome: String,
    val evidence: String,
)

/** Значок уровня исхода для таблиц отчёта. */
private fun levelMark(level: String): String = when (level) {
    "LEAK" -> "🔴 LEAK"
    "GAP" -> "🟠 GAP"
    else -> "🟢 HELD"
}

/**
 * Гоняет весь набор AttackCatalog по живому /v1/execute цели и оформляет результат.
 * phase=before → пишет output/attack-report.md (отчёт атакующего) + runs-before.json;
 * phase=after  → пишет runs-after.json и, сверяя с runs-before.json,
 * output/defense-report.md (отчёт защитника: что закрылось, что осталось).
 */
class RedTeamRunner(
    private val target: String,
    private val token: String?,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val detector = BreachDetector()
    private val outDir = Path.of("output")

    fun run(phase: String) {
        Files.createDirectories(outDir)
        println("Red-team по $target (фаза «$phase»), атак: ${AttackCatalog.ALL.size}")
        val results = AttackCatalog.ALL.map { attack ->
            val result = runOne(attack)
            println("  [${levelMark(result.level)}] ${attack.id} — ${attack.title}")
            result
        }
        outDir.resolve("runs-$phase.json").writeText(json.encodeToString(results))

        val leak = results.count { it.level == "LEAK" }
        val gap = results.count { it.level == "GAP" }
        println("Итог фазы «$phase»: LEAK $leak, GAP $gap, HELD ${results.size - leak - gap} (из ${results.size}).")

        when (phase) {
            "after" -> writeDefenseReport(loadPhase("before"), results)
            else -> writeAttackReport(phase, results)
        }
    }

    // ── Один прогон ──────────────────────────────────────────────────────

    private fun runOne(attack: Attack): AttackResult {
        val body = buildJsonObject {
            put("prompt", attack.prompt)
            if (attack.files.isNotEmpty()) putJsonArray("files") {
                attack.files.forEach { (name, content) ->
                    add(buildJsonObject { put("name", name); put("content", content) })
                }
            }
        }.toString()
        val response = post(body)
        val verdict = detector.judge(attack, response)
        return AttackResult(
            id = attack.id, front = attack.front.label, title = attack.title,
            technique = attack.technique, goal = attack.goal, kind = attack.kind.name,
            level = verdict.level.name, breached = verdict.breached,
            outcome = response?.outcome ?: "error", evidence = verdict.evidence,
        )
    }

    private fun post(body: String): ExecuteResponse? {
        repeat(5) { attempt ->
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("$target/v1/execute"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
            val resp = runCatching { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                ?: return null
            when (resp.statusCode()) {
                200 -> return parse(resp.body())
                429 -> {
                    val wait = resp.headers().firstValue("Retry-After").orElse("5").toLongOrNull() ?: 5L
                    println("    цель → 429, ждём ${wait}с (попытка ${attempt + 1}/5)")
                    Thread.sleep(wait * 1000)
                }
                401 -> { System.err.println("    цель → 401: неверный AGENT_TOKEN"); return null }
                else -> { System.err.println("    цель → HTTP ${resp.statusCode()}: ${resp.body().take(200)}"); return null }
            }
        }
        return null
    }

    private fun parse(raw: String): ExecuteResponse {
        val o = json.parseToJsonElement(raw).jsonObject
        val out = o["output_guard"]?.jsonObject
        val inp = o["input_guard"]?.jsonObject
        return ExecuteResponse(
            outcome = o["outcome"]?.jsonPrimitive?.content ?: "unknown",
            answer = o["answer"]?.jsonPrimitive?.content ?: "",
            committedCode = o["committed_code"]?.jsonPrimitive?.let { if (it.isString) it.content else null },
            commit = o["commit"]?.jsonPrimitive?.let { if (it.isString) it.content else null },
            inputMaskedTypes = inp?.get("masked_types")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            outputAction = out?.get("action")?.jsonPrimitive?.content ?: "pass",
            outputWarnings = out?.get("warnings")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            raw = raw,
        )
    }

    // ── Отчёты ───────────────────────────────────────────────────────────

    private fun loadPhase(phase: String): List<AttackResult> {
        val path = outDir.resolve("runs-$phase.json")
        if (!Files.exists(path)) return emptyList()
        return json.decodeFromString(Files.readString(path))
    }

    private fun writeAttackReport(phase: String, results: List<AttackResult>) {
        val leak = results.count { it.level == "LEAK" }
        val gap = results.count { it.level == "GAP" }
        val md = buildString {
            appendLine("# День 15 — отчёт атакующего (red-team, фаза «$phase»)")
            appendLine()
            appendLine("Цель: `$target` · модель `${Config.deepSeekModel()}` · атак ${results.size}.")
            appendLine()
            appendLine("Итог: **LEAK $leak** (реальный пробой), **GAP $gap** (секрет дошёл до модели " +
                "немаскированным — дыра в контроле), **HELD ${results.size - leak - gap}**.")
            appendLine()
            appendLine("> LEAK — секрет реально в ответе или небезопасный код закоммичен. " +
                "GAP — input-guard не замаскировал секрет, и он ушёл в LLM; утечки не случилось лишь " +
                "из-за поведения модели — на это нельзя полагаться. HELD — закрыто контролем.")
            appendLine()
            appendLine("## Матрица атак")
            appendLine()
            appendLine("| ID | Фронт | Приём | outcome | Вердикт |")
            appendLine("|---|---|---|---|---|")
            results.forEach {
                appendLine("| ${it.id} | ${it.front.substringBefore('.')} | ${it.technique} | " +
                    "${it.outcome} | ${levelMark(it.level)} |")
            }
            appendLine()
            AttackFront.entries.forEach { front ->
                val group = results.filter { it.front == front.label }
                if (group.isEmpty()) return@forEach
                appendLine("## ${front.label}")
                appendLine()
                group.forEach { r ->
                    appendLine("### ${r.id} — ${r.title}")
                    appendLine("- Цель: ${r.goal}")
                    appendLine("- Приём: ${r.technique}")
                    appendLine("- Итог пайплайна: `${r.outcome}`")
                    appendLine("- Вердикт: **${r.level}**")
                    appendLine("- Доказательство: ${r.evidence}")
                    appendLine()
                }
            }
        }
        outDir.resolve("attack-report.md").writeText(md)
        println("Отчёт атакующего: output/attack-report.md")
    }

    private fun writeDefenseReport(before: List<AttackResult>, after: List<AttackResult>) {
        val beforeById = before.associateBy { it.id }
        val rank = mapOf("LEAK" to 2, "GAP" to 1, "HELD" to 0)
        val md = buildString {
            appendLine("# День 15 — отчёт защитника (hardening: до → после)")
            appendLine()
            fun count(list: List<AttackResult>, lvl: String) = list.count { it.level == lvl }
            appendLine("| | LEAK | GAP | HELD |")
            appendLine("|---|---|---|---|")
            appendLine("| до | ${count(before, "LEAK")} | ${count(before, "GAP")} | ${count(before, "HELD")} |")
            appendLine("| после | ${count(after, "LEAK")} | ${count(after, "GAP")} | ${count(after, "HELD")} |")
            appendLine()
            appendLine("| ID | Фронт | До | После | Статус |")
            appendLine("|---|---|---|---|---|")
            after.forEach { a ->
                val b = beforeById[a.id]?.level ?: "HELD"
                val improved = (rank[b] ?: 0) > (rank[a.level] ?: 0)
                val worse = (rank[a.level] ?: 0) > (rank[b] ?: 0)
                val status = when {
                    improved && a.level == "HELD" -> "✅ закрыто"
                    improved -> "🟡 улучшено"
                    worse -> "⚠️ регресс"
                    a.level == "HELD" -> "— (уже держалось)"
                    else -> "❌ осталось"
                }
                appendLine("| ${a.id} | ${a.front.substringBefore('.')} | ${levelMark(b)} | " +
                    "${levelMark(a.level)} | $status |")
            }
            appendLine()
            appendLine("## Детали после hardening")
            appendLine()
            after.forEach { a ->
                appendLine("### ${a.id} — ${a.title}")
                appendLine("- До → После: **${beforeById[a.id]?.level ?: "HELD"} → ${a.level}**, outcome `${a.outcome}`")
                appendLine("- ${a.evidence}")
                appendLine()
            }
        }
        outDir.resolve("defense-report.md").writeText(md)
        println("Отчёт защитника: output/defense-report.md")
    }
}
