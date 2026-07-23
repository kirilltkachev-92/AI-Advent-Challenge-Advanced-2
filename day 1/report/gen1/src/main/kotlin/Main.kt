/**
 * День 1. HTTP-сервис «мотиватор».
 *
 * Принимает описание задачи и возвращает короткую мотивационную фразу
 * от DeepSeek; последние 10 пар запрос/ответ хранятся в памяти.
 * Main — только wiring: Config → зависимости → сервер.
 */
fun main() {
    Config.loadDotEnv()

    val apiKey = Config.deepSeekApiKey()
    if (apiKey == null) {
        System.err.println(
            "DEEPSEEK_API_KEY не задан — сервису нечем генерировать фразы.\n" +
                "Скопируйте .env.example в .env и впишите ключ.",
        )
        return
    }

    val motivator = Motivator(DeepSeekClient(apiKey))
    val history = HistoryStore(capacity = 10)
    HttpApi(motivator, history).start()

    println(
        """
        |✓ Мотиватор запущен: http://${Config.bindHost()}:${Config.port()}
        |  здоровье:  GET  /healthz
        |  мотивация: POST /v1/motivate   {"task": "..."}
        |  история:   GET  /v1/history    (последние 10, свежие первыми)
        |
        |  модель: ${Config.deepSeekModel()} · task ≤ ${Config.maxTaskChars()} симв.
        """.trimMargin(),
    )
}
