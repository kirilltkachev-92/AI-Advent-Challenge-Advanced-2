```kotlin
=== FILE: MainKt.kt ===
import java.net.http.HttpClient
import com.sun.net.httpserver.HttpServer

fun main() {
    val server = HttpServer.create("localhost", 8080)
    server.createContext("/healthz") { HealthHandler.handle(it) }
    server.createContext("/v1/motivate") { MotivationHandler.handle(it) }
    server.createContext("/v1/history") { HistoryHandler.handle(it) }
    server.start()
}

object Config {
    const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"
}
=== END ===
=== FILE: HealthHandler.kt ===
import com.sun.net.httpserver.HttpExchange

object HealthHandler {
    fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, 0)
            return
        }
        exchange.responseHeaders.set("Content-Type", "text/plain")
        exchange.sendResponseHeaders(200, 0)
    }
}
=== END ===
=== FILE: MotivationHandler.kt ===
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object MotivationHandler {
    private val client = HttpClient.newHttpClient()
    private val mapper = jacksonObjectMapper()

    fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(405, 0)
            return
        }
        val requestBody = exchange.requestBody.readAllBytes().decodeToString()
        val requestJson = mapper.readTree(requestBody)

        val task = requestJson.get("task").textValue()

        val response = client.send(
            HttpRequest.newBuilder()
                .uri("https://api.deepseek.com/v1/motivate")
                .header("Authorization", "Bearer ${Config.DEEPSEEK_API_KEY}")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} ${response.body().take(300)}" }
        val responseJson = mapper.readTree(response.body())
        val motivation = responseJson.get("motivation").textValue()

        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(motivation.encodeToByteArray())
    }
}
=== END ===
=== FILE: HistoryHandler.kt ===
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object HistoryHandler {
    private val mapper = jacksonObjectMapper()
    private val history = mutableListOf<JsonNode>()

    fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, 0)
            return
        }
        val responseJson = mapper.createObjectNode().apply {
            putArray("history").addAll(history.takeLast(10))
        }

        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(responseJson.toString().encodeToByteArray())
    }
}
=== END ===
```

build.gradle.kts:
```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
}

tasks.test {
    useJUnitPlatform()
}
```