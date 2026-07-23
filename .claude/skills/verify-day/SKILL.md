---
name: verify-day
description: Проверка подпроекта "day N" — сборка, тесты, соответствие структуре. Используй перед тем как объявить задачу сделанной, и перед коммитом.
---

# Проверка дня

Прогони по порядку из папки `day N/` (JDK 17 подхватывается через run.sh-бутстрап или JAVA_HOME):

1. **Сборка**: `./gradlew compileKotlin --console=plain` — обязана пройти без ошибок.
2. **Тесты** (если есть src/test): `./gradlew test --console=plain`.
3. **Структура** — все файлы на месте:
   `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradle/`, `run.sh`, `.env.example`, `README.md`, `src/main/kotlin/`.
4. **Быстрые greps**:
   - `grep -rn "^package " src/` → должно быть пусто (default package);
   - `grep -rn "sk-[A-Za-z0-9]\{10,\}" src/ *.kts` → должно быть пусто (секреты);
   - `grep -n "ktor\|okhttp\|retrofit\|spring" build.gradle.kts` → должно быть пусто (SDK запрещены);
   - `wc -l src/main/kotlin/Main.kt` → не больше ~300 строк.
5. Если сервис HTTP — подними его и дерни endpoint curl'ом (happy path + один невалидный запрос).

Формат ответа: таблица «проверка → PASS/FAIL», и список исправлений, если есть FAIL.
