# AI Advent Challenge — Advanced 2 (правила проекта, v2)

Марафон подпроектов «day N»: каждый день — маленький самостоятельный Kotlin-проект
(агент, сервис, CLI). Образец house style — прошлый марафон `../AI-Advent-Challenge-8`
(day 1–35): при сомнении смотри самый свежий день с похожей задачей.

## Стек

- Kotlin **2.0.21**, JVM **17**, Gradle (wrapper копируется из прошлого дня).
- Единственная зависимость: `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`.
- HTTP-клиент — только `java.net.http.HttpClient` (JDK), сервер — только
  `com.sun.net.httpserver.HttpServer` (JDK). **Никаких** ktor/okhttp/retrofit/spring.
- LLM — DeepSeek (OpenAI-совместимый `/chat/completions`), протокол пишем руками,
  без SDK. Ключ — `DEEPSEEK_API_KEY` из env/`.env`.

## Архитектура

Каждый `day N/` — независимый Gradle-проект без общего кода с другими днями.
Слои внутри дня (каждый — отдельный файл):

- `Config` — object, читает env + `.env`, отдаёт типизированные значения;
- клиенты внешних API (`DeepSeekClient`, …) — тонкие, протокол вручную;
- доменная логика (агент/индексер/state machine) — чистые классы без I/O наружу;
- `HttpApi`/CLI-слой — только транспорт: парсинг запроса, коды ошибок, JSON-ответ;
- `Main.kt` — только wiring: загрузить Config, собрать зависимости, запустить.

## Структура папок

```
day N/
├── build.gradle.kts        # kotlin-jvm + serialization, application, JVM 17
├── settings.gradle.kts     # rootProject.name = "day-N"
├── gradlew, gradlew.bat, gradle/
├── run.sh                  # запуск одной командой; бутстрап JAVA_HOME (openjdk@17)
├── demo.sh                 # curl-сценарий демо «для видео»: нумерованные шаги ── N. … ──
├── .gitignore              # .gradle/ build/ .idea/ *.iml .env .kotlin/ + генерируемое из output/
├── .env.example            # DEEPSEEK_API_KEY=sk-xxxx (плейсхолдер!)
├── README.md               # структура — см. «Шаблон README» ниже
├── output/                 # все генерируемые артефакты: отчёты, логи (_run.log); в .gitignore
└── src/main/kotlin/        # плоско, БЕЗ package-деклараций (default package)
    ├── Main.kt
    ├── Config.kt
    └── <OneTypePerFile>.kt
```

- Порт HTTP-сервиса кодирует день: **8000 + N** (day 1 → 8001, day 30 → 8030);
  переопределяется через `PORT` в env.
- `run.sh` пишет лог запуска: `./gradlew run -q --console=plain … 2>&1 | tee output/_run.log`.
- Тесты по умолчанию не пишем (как в днях 21–35 образца). Если задача явно требует —
  `kotlin-test` + `junit-jupiter:5.11.3`, каталог `src/test/kotlin`.
- Graceful shutdown не требуется: сервис живёт до Ctrl+C, shutdown-hook'и не изобретать.

## Naming conventions

- Файлы: PascalCase, один основной тип = один файл, имя файла = имя типа.
- Никаких `package`-деклараций — весь код в default package.
- `mainClass = "MainKt"`, точка входа — `fun main()` в `Main.kt`.
- Классы/объекты — существительные (`ReleaseAgent`, `IndexStore`); функции — глаголы.
- Идентификаторы и commit-сообщения — английские; комментарии и строки UI — русские.
- JSON-поля внешних протоколов — как в протоколе (snake_case), внутренние DTO — camelCase.

## Паттерны

1. **Config-object + .env** — секреты только из env; `.env` в `.gitignore`,
   в репозитории — `.env.example` с плейсхолдером.
2. **Тонкий HTTP-клиент**: таймауты (connect ~10s, request — по задаче),
   `check(status == 200) { …body.take(300) }`, `Json { ignoreUnknownKeys = true }`.
3. **Sealed-результаты** для операций с исходами (`Moved`/`Rejected`), а не исключения
   для бизнес-логики.
4. **Оборона на входе HTTP** — строго в этом порядке, и KDoc класса обязан ему
   соответствовать: метод (405) → авторизация (401) → rate limit (429) →
   размер тела по `Content-Length` ДО чтения (413, тело читать `readNBytes` с потолком,
   не безлимитный `readBytes()`) → парсинг/валидация тела (400).
   Формат ошибок — вложенный, как в day 30 образца: `{"error": {"code": "...", "message": "..."}}`.
5. **Access-лог**: каждый запрос через обёртку `handle()` логируется одной строкой —
   метод, путь, статус, адрес клиента, длительность в мс (`"%s %s %s ← %s за %d мс"`).
6. **Комментарии-документация**: KDoc над классом объясняет «почему и контракт»,
   а не пересказывает код.

### Примеры хорошего кода (из `../AI-Advent-Challenge-8`)

**№1 — Config: env → .env, типизированные геттеры** (day 35, `Config.kt`):

```kotlin
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(Path.of(".env")).forEach { path ->
            if (path.exists()) {
                path.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                    val idx = trimmed.indexOf('=')
                    if (idx <= 0) return@forEach
                    dotEnv.putIfAbsent(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim())
                }
            }
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    fun deepSeekApiKey(): String? = envValue("DEEPSEEK_API_KEY")
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-chat"
}
```

**№2 — клиент LLM без SDK: протокол руками, таймауты, проверка статуса** (day 25, `DeepSeekClient.kt`):

```kotlin
class DeepSeekClient(
    private val apiKey: String = Config.deepSeekKey(),
    private val model: String = Config.deepSeekModel(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun chat(system: String, user: String, jsonMode: Boolean = false): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("temperature", 0.0)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "DeepSeek → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return json.parseToJsonElement(response.body()).jsonObject
            .getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
    }
}
```

**№3 — HTTP-слой на JDK: контракт в KDoc, оборона по порядку** (day 30, `HttpApi.kt`):

```kotlin
/**
 * HTTP-слой сервиса — com.sun.net.httpserver из JDK, без фреймворков.
 * Публичное:  GET /healthz — жив ли сервис
 * По токену:  POST /v1/chat — {messages:[…]} → ответ
 * Порядок обороны: 401 (нет токена) → 429 (rate limit) → 413 → 503.
 */
class HttpApi(private val chat: ChatService) {
    private val json = Json { ignoreUnknownKeys = true }

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(Config.bindHost(), Config.port()), 0)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/healthz") { ex ->
            handle(ex) { send(ex, 200, buildJsonObject { put("status", "ok") }) }
        }
        server.start()
        return server
    }
}
```

**№4 — sealed-результат вместо исключений в бизнес-логике** (day 14, `TaskState.kt`):

```kotlin
/** Результат попытки перехода между этапами. */
sealed interface TransitionResult {
    data class Moved(val from: TaskStage, val to: TaskStage) : TransitionResult
    data class Rejected(val reason: String, val allowed: Set<TaskStage>) : TransitionResult
}

/** Валидируемый переход. Недопустимый переход не меняет состояние. */
fun moveTo(target: TaskStage): TransitionResult {
    if (!canMoveTo(target)) {
        return TransitionResult.Rejected("переход «${stage.label}» → «${target.label}» запрещён", allowedNext)
    }
    val from = stage
    stage = target
    return TransitionResult.Moved(from, target)
}
```

## Антипаттерны (запрещено)

1. **Фреймворки и SDK**: ktor/okhttp/retrofit/spring/LLM-SDK в `build.gradle.kts`.
   Весь HTTP и протоколы LLM — руками на JDK.
2. **Секреты в коде или git**: ключи только через env/`.env`; `.env` не коммитится,
   в `.env.example` — плейсхолдер.
3. **God-Main.kt**: >~300 строк или бизнес-логика в `Main.kt`. Main — только wiring.
4. **Шаринг кода между днями**: никаких `import`/чтения исходников `../day N/*`.
   Нужен тот же клиент — скопируй файл в свой день.
5. **Глотание ошибок HTTP**: ответ не-2xx нельзя молча парсить или игнорировать —
   всегда `check`/явная ошибка с кодом и куском тела.

## Шаблон типичного файла

```kotlin
// 1. Импорты: kotlinx.serialization → java.* → без wildcard'ов
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.http.HttpClient
import java.time.Duration

/**
 * 2. KDoc: зачем существует тип, его контракт (вход → выход), неочевидные решения.
 */
class SomeService(
    // 3. Зависимости — через конструктор со значениями по умолчанию из Config
    private val client: DeepSeekClient = DeepSeekClient(),
) {
    // 4. Приватное состояние
    private val json = Json { ignoreUnknownKeys = true }

    // 5. Публичный API — маленькие функции с понятными именами
    fun run(input: String): SomeResult { /* … */ }

    // 6. Приватные помощники — внизу
    private fun parse(raw: String): SomeResult { /* … */ }
}

// 7. DTO рядом с типом, который их использует (если не нужны в 2+ файлах)
@Serializable
data class SomeResult(val ok: Boolean, val message: String)
```

## Шаблон README

```markdown
# День N. <Название>

<1–2 абзаца: что делает и зачем.>

## Как устроено
<кратко про файлы/поток данных; для сервисов — ASCII-диаграмма потока запроса>

## Запуск
<./run.sh, ./demo.sh; таблица env-переменных: имя, дефолт, зачем>

## Чеклист задания
- [x] <пункты условия дня и чем они закрыты>
```

## Workflow

- Стадии задачи: research → plan → execute → validate (см. глобальный профиль).
- Скаффолдинг нового дня — скилл `new-day`; проверка перед сдачей — скилл `verify-day`.
- Реализацию боевых задач делает субагент `day-builder`, ревью — `day-reviewer`.
- День не считается сделанным без `./gradlew compileKotlin` и (для сервисов) живого
  curl-запроса к поднятому серверу.
