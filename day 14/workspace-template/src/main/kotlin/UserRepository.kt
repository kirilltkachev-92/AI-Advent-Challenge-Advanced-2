/** Пользователь демо-приложения. Все данные фикстур — фейковые (example.com, тестовая карта). */
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String,
    val cardNumber: String,
)

/**
 * In-memory репозиторий с ФЕЙКОВЫМИ PII-фикстурами: email, телефоны и тестовая
 * карта 4111… (валидный Luhn) закопаны нарочно — файл уходит контекстом
 * в generation-промпт, где их обязан замаскировать input guard шлюза.
 */
class UserRepository {
    private val users = listOf(
        User(1, "Ivan Petrov", "ivan.petrov@example.com", "+7 900 000-00-00", "4111 1111 1111 1111"),
        User(2, "Anna Sidorova", "anna.sidorova@example.com", "+7 900 000-00-01", "5500 0000 0000 0004"),
    )

    fun findAll(): List<User> = users

    fun findById(id: Long): User? = users.firstOrNull { it.id == id }
}
