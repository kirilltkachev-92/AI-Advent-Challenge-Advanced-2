=== FILE: Main.kt ===
```kotlin
fun main() {
    val server = HttpServer.create(InetSocketAddress(8080), 0)
    server.createContext("/healthz", HealthCheckHandler())
    server.createContext("/v1/motivate", MotivationHandler())
    server.createContext("/v1/history", HistoryHandler())
    server.start()
}
=== END ===

=== FILE: HealthCheckHandler.kt ===
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.IOException
import java.io.OutputStream

class HealthCheckHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "{\"error\":{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Method not allowed\"}}")
            return
        }
        val response = "{\"status\":\"UP\"}"
        sendResponse(exchange, 200, response)
    }

    private fun sendResponse(exchange: HttpExchange, status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.length.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(body.toByteArray())
        os.close()
    }
}
=== END ===

=== FILE: MotivationHandler.kt ===
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class TaskRequest(val task: String)
data class MotivationResponse(val motivation: String)

class MotivationHandler(private val client: ChatClient) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            sendResponse(exchange, 405, "{\"error\":{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Method not allowed\"}}")
            return
        }
        val requestBody = exchange.getRequestBody().use { it.readAllBytes() }.toString(Charsets.UTF_8)
        try {
            val taskRequest = Json.decodeFromString<TaskRequest>(requestBody)
            val motivationResponse = client.getMotivation(taskRequest.task)
            sendResponse(exchange, 200, "{\"motivation\":\"${motivationResponse.motivation}\"}")
        } catch (e: Exception) {
            sendResponse(exchange, 400, "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"Invalid request\"}}")
        }
    }

    private fun sendResponse(exchange: HttpExchange, status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.length.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(body.toByteArray())
        os.close()
    }
}
=== END ===

=== FILE: HistoryHandler.kt ===
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RequestResponse(val request: String, val response: String)

class HistoryHandler : HttpHandler {
    private val history = mutableListOf<RequestResponse>()

    override fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "{\"error\":{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Method not allowed\"}}")
            return
        }
        val response = Json.encodeToString(history.takeLast(10))
        sendResponse(exchange, 200, response)
    }

    private fun sendResponse(exchange: HttpExchange, status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.length.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(body.toByteArray())
        os.close()
    }
}
=== END ===

=== FILE: ChatClient.kt ===
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

interface ChatClient {
    fun getMotivation(task: String): MotivationResponse
}

class DeepSeekChatClient : ChatClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()

    override fun getMotivation(task: String): MotivationResponse {
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI("https://api.deepseek.com/motivate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"$task\"}"))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return MotivationResponse(response.body())
    }
}
=== END ===

=== FILE: build.gradle.kts ===
plugins {
    kotlin("jvm") version "1.7.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
=== END ===