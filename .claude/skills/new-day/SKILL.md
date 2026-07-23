---
name: new-day
description: Скаффолдинг нового подпроекта "day N" в house style марафона. Используй, когда нужно завести каркас нового дня перед реализацией задачи.
---

# Скаффолдинг нового дня

Создай каркас `day N/` по шагам:

1. Возьми за основу самый свежий подходящий день из `../AI-Advent-Challenge-8`
   (консольный — day 35, HTTP-сервис — day 30, MCP — day 34, RAG — day 25).
2. Скопируй из него: `gradlew`, `gradlew.bat`, `gradle/`, `run.sh`, `.gitignore`,
   `local.properties.example` (если есть).
3. Создай `settings.gradle.kts` с `rootProject.name = "day-N"`.
4. Создай `build.gradle.kts`: kotlin("jvm") 2.0.21 + plugin.serialization, JVM 17,
   `application { mainClass = "MainKt" }`, зависимость kotlinx-serialization-json:1.7.3.
   Для консольного REPL добавь `tasks.named<JavaExec>("run") { standardInput = System.`in` }`.
5. Создай `.env.example` с `DEEPSEEK_API_KEY=sk-xxxx`.
6. Создай `src/main/kotlin/Config.kt` (парсер .env из CLAUDE.md, пример №1) и пустой `Main.kt`.
7. Создай `README.md`: заголовок дня, что делает, как запустить (`./run.sh`), требования (.env).
8. Проверь каркас: `./gradlew compileKotlin` должен пройти.
