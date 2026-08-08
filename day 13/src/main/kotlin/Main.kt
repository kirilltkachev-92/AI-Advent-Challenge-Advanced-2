/** Точка входа: только wiring — конфиг, зависимости, запуск шлюза. */
fun main() {
    Config.loadDotEnv()

    val scanner = SecretScanner()
    val apiKey = Config.deepSeekApiKey()
    if (apiKey == null) {
        println("DEEPSEEK_API_KEY не задан: block/mask работают, проксирование в LLM вернёт 503")
    }
    val api = HttpApi(
        inputGuard = InputGuard(scanner),
        outputGuard = OutputGuard(scanner),
        client = apiKey?.let { DeepSeekClient(it) },
        audit = AuditLog(),
        costs = CostTracker(),
    )
    api.start()
    println("LLM Gateway: http://${Config.bindHost()}:${Config.port()} (модель ${Config.deepSeekModel()}, лимит ${Config.rateLimitPerMin()}/мин)")
}
