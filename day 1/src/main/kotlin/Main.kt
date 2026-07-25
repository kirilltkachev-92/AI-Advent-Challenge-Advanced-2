/**
 * День 1. HTTP-сервис «мотиватор».
 *
 * POST /v1/motivate принимает задачу и возвращает короткую мотивационную
 * фразу от DeepSeek; история последних 10 обменов доступна на GET /v1/history.
 * Main — только wiring: конфиг, зависимости, старт сервера.
 */
fun main() {
    Config.loadDotEnv()

    val apiKey = Config.deepSeekApiKey()
    if (apiKey == null) {
        System.err.println(
            "DEEPSEEK_API_KEY не задан — без него /v1/motivate не работает.\n" +
                "Скопируйте .env.example в .env и впишите ключ.",
        )
        return
    }

    val motivator = Motivator(DeepSeekClient(apiKey))
    HttpApi(motivator, HistoryStore()).start()

    // HISTORY_SIZE <= 0 отключает историю (см. HistoryStore) — отражаем это в баннере
    val historyNote =
        if (Config.historySize() <= 0) "отключена, HISTORY_SIZE <= 0"
        else "последние ${Config.historySize()}, в памяти"
    println(
        """
        |✓ Мотиватор запущен: http://${Config.bindHost()}:${Config.port()}
        |  здоровье:   GET  /healthz
        |  мотивация:  POST /v1/motivate   {"task": "…"}
        |  история:    GET  /v1/history    ($historyNote)
        |  модель: ${Config.deepSeekModel()}
        """.trimMargin(),
    )
}
