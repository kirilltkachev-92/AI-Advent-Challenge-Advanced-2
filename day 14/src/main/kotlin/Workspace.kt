import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Рабочая копия фейковой кодовой базы: workspace-template/ (в git) копируется
 * в output/workspace/ (в .gitignore), там делается `git init` + initial commit —
 * дальше луп пишет сгенерированные файлы и коммитит итерации именно туда.
 * В шаблоне НАРОЧНО закопаны фейковые секреты (sk-ключ, email, телефон, карта):
 * файлы уходят контекстом в generation-промпт, и ловить их обязан input guard
 * шлюза — находки видны в output/audit.jsonl замаскированными фрагментами.
 */
class Workspace(
    private val template: Path = Path.of("workspace-template"),
    val root: Path = Path.of("output/workspace"),
) {
    private val sources = root.resolve("src/main/kotlin")

    /** Свежая копия шаблона + git init + initial commit. Старая копия сносится. */
    fun reset() {
        check(template.exists()) { "нет шаблона $template" }
        if (root.exists()) {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        Files.walk(template).filter { it.isRegularFile() }.forEach { src ->
            val dst = root.resolve(src.relativeTo(template))
            Files.createDirectories(dst.parent)
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", "initial: workspace template with planted fake secrets")
    }

    /** Контекст для generation-промпта: все текущие .kt workspace — шаблон плюс уже закоммиченные файлы прошлых задач. */
    fun contextFiles(): Map<String, String> =
        Files.list(sources).use { stream ->
            stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .sorted()
                .toList()
                .associate { it.fileName.toString() to it.readText() }
        }

    /** Записывает сгенерированный файл задачи в src/main/kotlin. */
    fun writeGenerated(fileName: String, code: String) {
        val target = sources.resolve(fileName)
        Files.createDirectories(target.parent)
        target.writeText(if (code.endsWith("\n")) code else code + "\n")
    }

    /**
     * Коммит ТОЛЬКО файла задачи (не -A: отвергнутые файлы других задач не должны
     * просачиваться в чужой коммит). Возвращает короткий хэш.
     */
    fun commit(message: String, fileName: String): String {
        git("add", "src/main/kotlin/$fileName")
        git("commit", "-q", "-m", message)
        return git("rev-parse", "--short", "HEAD").trim()
    }

    /** Провал задачи: сгенерированный файл убирается, в git он не попадёт. */
    fun discard(fileName: String) {
        Files.deleteIfExists(sources.resolve(fileName))
    }

    fun readFile(fileName: String): String = sources.resolve(fileName).readText()

    /** git -C <root> с фиксированной identity: не зависит от глобального конфига. */
    private fun git(vararg args: String): String {
        val cmd = listOf(
            "git", "-C", root.toString(),
            "-c", "user.name=day14-loop", "-c", "user.email=loop@day14.local",
        ) + args
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} → exit $code: ${out.take(300)}" }
        return out
    }
}
