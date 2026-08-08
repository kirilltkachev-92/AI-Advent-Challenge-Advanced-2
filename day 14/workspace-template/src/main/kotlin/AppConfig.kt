/**
 * Конфиг демо-приложения. ВСЕ значения ниже — ЗАВЕДОМО ФЕЙКОВЫЕ и закопаны
 * нарочно: файл уходит контекстом в generation-промпт, и ловить sk-ключ обязан
 * input guard шлюза. DB_PASSWORD без известного префикса — осознанная слепая
 * зона сканера (см. отчёт).
 */
object AppConfig {
    const val API_BASE_URL = "https://api.example.com/v1"
    const val API_KEY = "sk-fake-demo000000000000"
    const val DB_URL = "jdbc:postgresql://localhost:5432/appdb"
    const val DB_USER = "app"
    const val DB_PASSWORD = "correct-horse-battery-staple"
}
