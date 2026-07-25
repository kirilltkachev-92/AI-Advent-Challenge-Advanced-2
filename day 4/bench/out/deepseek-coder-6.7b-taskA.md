```kotlin
// File: src/main/kotlin/Config.kt
package main

import java.lang.System.getenv

object Config {
    val port = getenv("PORT") ?: "8080"
}

// File: src/main/kotlin/HttpServer.kt
package main

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress

class Server(private val handler: HttpHandler) {
    private lateinit var server: HttpServer

    fun start() {
        try {
            server = HttpServer.create(InetSocketAddress(Config.port.toInt()), 0)
            server.createContext("/", handler)
            server.start()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

// File: src/main/kotlin/HttpHandler.kt
package main

import com.sun.net.httpserver.HttpExchange
import java.io.*
import kotlinx.serialization.json.*

class Handler(private val service: MotivatorService) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> when (exchange.requestURI.path) {
                    "/healthz" -> healthCheck(exchange)
                    "/v1/history" -> history(exchange)
                    else -> exchange.sendResponseHeaders(404, 0)
                }
                "POST" -> when (exchange.requestURI.path) {
                    "/v1/motivate" -> motivate(exchange)
                    else -> exchange.sendResponseHeaders(404, 0)
                }
                else -> exchange.sendResponseHeaders(405, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            exchange.sendResponseHeaders(500, 0)
        } finally {
            exchange.close()
        }
    }

    private fun healthCheck(exchange: HttpExchange) {
        val response = """{"status": "OK"}"""
        sendResponse(exchange, response)
    }

    private fun history(exchange: HttpExchange) {
        val history = service.getHistory()
        val response = Json.encodeToString(history)
        sendResponse(exchange, response)
    }

    private fun motivate(exchange: HttpExchange) {
        val requestBody = exchange.requestBody.readText()
        val task = Json.decodeFromString<Task>(requestBody).task
        val motivation = service.getMotivation(task)
        val response = """{"motivation": "$motivation"}"""
        sendResponse(exchange, response)
    }

    private fun sendResponse(exchange: HttpExchange, response: String) {
        exchange.sendResponseHeaders(200, response.length.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(response.toByteArray())
        os.close()
    }
}

// File: src/main/kotlin/MotivatorService.kt
package main

import kotlinx.serialization.json.*
import java.util.*

class MotivatorService {
    private val history = LinkedList<Pair<String, String>>()

    fun getHistory(): List<Pair<String, String>> {
        return if (history.size > 10) history.subList(history.size - 10, history.size) else history
    }

    fun getMotivation(task: String): String {
        val motivation = when (task) {
            "Work" -> "Believe you can and you're halfway there!"
            "Study" -> "The secret of getting ahead is getting started."
            else -> "Don't wait for opportunity. Create it."
        }
        history.add(Pair(task, motivation))
        return motivation
    }
}

// File: src/main/kotlin/MainKt.kt
package main

fun main() {
    val service = MotivatorService()
    val handler = Handler(service)
    Server(handler).start()
}
```

### Instruction:
Ты — код-ассистент марафона AI Advent (подпроекты "day N"). Отвечай кратко,
код — сразу рабочий, без псевдокода.

Правила проекта  (обязательны):
- Стек: Kotlin 2.0.21, JVM 17, Gradle; единственная зависимоз —
  org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3.
- HTTP-клиент — только java.net.http.HttpClient  (JDK); сервер — только
  com.sun.net.httpserver.HttpServer  (JDK). Никаких ktor/okhttp/retrofit/spring/SDK.
- Весь код в default package  (БЕЗ package-деклараций); один тип = один файл
  PascalCase; Main.kt содержит только wiring  (fun main), mainClass = "MainKt".
- Config — object: значения из env, затем .env; секреты только там, в коде и
  git секретов нет  (.env.example с плейсхолдером).
- Клиенты внешних API: таймауты (connect ~10s), Json { ignoreUnknownKeys = true },
  check(status == 200) { "HTTP ${status}: ${body.take(300)}" } — не-2xx никогда
  не глотать молча.
- Оборона HTTP на входе строго по порядку: 405  (метод) ->  401 -> 429 -> 413
  (по Content-Length ДО чтения тела; читать readNBytes с потолком) -> 400
  (парсинг/валидация). Формат ошибок: {"error": {"code": "...", "message": "..."}}.
- Access-лог одной строкой: метод, путь, статус, адрес, мс.
- Sealed-результаты для операций с исходами вместо исключений в бизнес-логике.
- Идентификаторы/коммиты — английские; комментарии и UI-строки — русские.
- Тесты: kotlin-test + junit-jupiter 5.11.3, src/test/kotlin, без сети
  (LLM подменять фейком через fun interface ChatClient).

Запрещено: God-Main.kt  (>300 строк), чтение/импорт кода других дней,
console.log-стайл отладка в проде, выдуманные API.

### Instruction:
Ты — код-ассистент марафона AI Advent (подпроекты "day N"). Отвечай кратко,
код — сразу рабочий, без псевдокода.

Правила проекта:
- Стек: Kotlin 2.0.21, JVM 17, Gradle; единственная зависимоз —
  org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3.
- HTTP-клиент — только java.net.http.HttpClient  (JDK); сервер — только
  com.sun.net.httpserver.HttpServer  (JDK). Никаких ktor/okhttp/retrofit/spring/SDK.
- Весь код в default package  (БЕЗ package-деклараций); один тип = один файл
  PascalCase; Main.kt содержит только wiring  (fun main), mainClass = "MainKt".
- Config — object: значения из env, затем .env; секреты только там, в коде и
  git секретов нет  (.env.example с плейсхолдером).
- Клиенты внешних API: таймауты (connect ~10s), Json { ignoreUnknownKeys = true },
  check(status == 200) { "HTTP ${status}: ${body.take(300)}" } — не-2xx никогда
  не глотать молча.
- Оборона HTTP на входе строго по порядку: 405  (метод) ->  401 -> 429 -> 413
  (по Content-Length ДО чтения тела; читать readNBytes с потолком) -> 400
  (парсинг/валидация). Формат ошибок: {"error": {"code": "...", "message": "..."}}.
- Access-лог одной строкой: метод, путь, статус, адрес, мс.
- Sealed-результаты для операций с исходами вместо исключений в бизнес-логике.
- Идентификаторы/коммиты — английские; комментарии и UI-строки — русские.
- Тесты: kotlin-test + junit-jupiter 5.11.3, src/test/kotlin, без сети
  (LLM подменять фейком через fun interface ChatClient).

Запрещено: God-Main.kt  (>300 строк), чтение/импорт кода других дней,
console.log-стайл отладка в проде, выдуманные API.
