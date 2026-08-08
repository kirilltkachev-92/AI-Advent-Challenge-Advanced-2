import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Итог tests/lint-gate: либо прошло, либо отказ с конкретным фидбеком для ретрая. */
sealed interface GateResult {
    data object Passed : GateResult
    data class Failed(val feedback: String) : GateResult
}

/**
 * Tests-gate лупа: (1) реальная компиляция workspace как отдельного gradle-проекта
 * (`./gradlew -p output/workspace compileKotlin` — gradlew берём свой, дневной,
 * wrapper в workspace не копируется); (2) предметные acceptance-проверки задачи
 * по regex'ам. Отказ возвращает хвост ошибок компилятора как фидбек генератору.
 */
class TestsGate(
    private val workspaceRoot: Path,
    private val gradlew: Path = Path.of("gradlew").toAbsolutePath(),
    private val timeoutSec: Long = 240,
) {

    fun check(task: ProvocationTask, code: String): GateResult {
        // Дешёвые проверки — до дорогого компилятора.
        failedAcceptance(task, code)?.let { return it }
        return compile()
    }

    private fun failedAcceptance(task: ProvocationTask, code: String): GateResult.Failed? {
        val missed = task.acceptance.filterNot { it.pattern.containsMatchIn(code) }
        if (missed.isEmpty()) return null
        return GateResult.Failed(
            "код не проходит приёмку задачи: " + missed.joinToString("; ") { it.description },
        )
    }

    private fun compile(): GateResult {
        val process = ProcessBuilder(
            gradlew.toString(), "-p", workspaceRoot.toString(), "compileKotlin", "-q", "--console=plain",
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return GateResult.Failed("компиляция не уложилась в $timeoutSec с")
        }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.exitValue() == 0) return GateResult.Passed
        // Генератору отдаём только строки с ошибками (+хвост как fallback) — не весь лог.
        val errors = out.lines().filter { "error:" in it.lowercase() }
        val feedback = if (errors.isNotEmpty()) errors.take(15).joinToString("\n")
        else out.lines().takeLast(15).joinToString("\n")
        return GateResult.Failed("код не компилируется:\n$feedback")
    }
}
