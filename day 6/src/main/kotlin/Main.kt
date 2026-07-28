import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Точка входа — только wiring и разбор подкоманд:
 *   generate            — real.jsonl + синтетика через DeepSeek → synthetic.jsonl, raw.jsonl
 *   prepare             — очистка + стратифицированный split 80/20 → train.jsonl, eval.jsonl
 *   validate [file...]  — валидация JSONL (по умолчанию train.jsonl и eval.jsonl); exit 1 при провале
 *   baseline            — 10 примеров eval через базовую модель → output/baseline.md
 *   finetune [--go]     — клиент файнтюна OpenAI; по умолчанию dry-run
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (val command = args.firstOrNull() ?: "validate") {
        "generate" -> DatasetGenerator().run()
        "prepare" -> DatasetPreparer().run()
        "validate" -> {
            val files = args.drop(1).ifEmpty { listOf("data/train.jsonl", "data/eval.jsonl") }
            val validator = DatasetValidator()
            val allOk = files.map { validator.report(Path.of(it)) }.all { it }
            if (!allOk) exitProcess(1)
        }
        "baseline" -> BaselineRunner().run()
        "finetune" -> FineTuneClient().run(go = args.contains("--go"))
        else -> {
            System.err.println("Неизвестная команда «$command». Доступно: generate | prepare | validate [file...] | baseline | finetune [--go]")
            exitProcess(2)
        }
    }
}
