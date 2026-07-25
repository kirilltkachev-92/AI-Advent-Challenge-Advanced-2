# CHANGELOG — сервис мотивационных фраз (day 1)

История изменений по git-логу (`git log --oneline -- "day 1"`), от старых к новым.
Записи сгруппированы по дням марафона; версии не нумеруются — привязка только к коммитам.

## День 1 — рождение сервиса

- `3bb44f8` — первый коммит: весь сервис целиком.
  - Kotlin/JVM, без фреймворков: `Config.kt` (env + `.env`), `DeepSeekClient.kt`
    (руками на `java.net.http`), `Motivator.kt`, `HistoryStore.kt` (кольцо в памяти),
    `HttpApi.kt` (транспорт на `com.sun.net.httpserver`), `Main.kt` (wiring).
  - Обвязка: `run.sh`, `demo.sh`, `.env.example`, README, REPORT, Gradle wrapper.
  - Артефакты дня в `report/`: gen1 (первая генерация) и diff `gen1-vs-gen2.diff`.

## День 2 — фикс HISTORY_SIZE + синхронизация доков

- `d76ead2` — багфикс через первый прогон bug-fix профиля:
  - `HISTORY_SIZE=0` ронял каждый `/v1/motivate` в 500 — `HistoryStore.add`
    делал `removeFirst()` на пустом deque. Теперь `capacity <= 0` отключает
    историю (записи не сохраняются), условие вытеснения — `size >= capacity`.
  - README синхронизирован (day-docs профиль): описан режим отключённой истории.

## День 3 — тесты, web UI, очистка истории

- `fa276eb` — тестирование и первый UI:
  - Unit/integration-тесты: `HistoryStoreTest`, `MotivatorTest`, `HttpApiTest`
    (интеграция на порту 0); шов `ChatClient.kt`, чтобы тесты шли без сети — 33/33.
  - Single-page web UI (`WebUi.kt`, `GET /`) со стабильными id для автоматизации.
  - Очистка истории: `DELETE /v1/history` + кнопка в UI.
  - UI smoke через Playwright нашёл реальный баг (пустой ввод молча глотался) — исправлен.

## Run-1 hardening — фикс модели DeepSeek

- `a16e98b` — fix #17: дефолтная модель заменена на `deepseek-v4-flash`
  (`Config.kt`, `.env.example`, README) + заметка в CLAUDE.md.

## Run 2 — текущий прогон (задачи run2#N)

### Bug-fix

- `bcbaf8d` — run2#1: стартовый баннер сообщает, что история отключена (`Main.kt`).
- `74906cb` — run2#2: время в истории UI форматируется как `HH:MM:SS` (`WebUi.kt`).
- `b5efd2c` — run2#3: `demo.sh` проверяет HTTP-коды на каждом шаге.

### Refactor

- `08d53e8` — run2#4: sealed `MotivationResult` в домене (`Motivator.kt`),
  `HttpApi` разбирает результат по типам; тесты обновлены.
- `c768b07` — run2#5: `/v1/history` отдаётся через kotlinx.serialization DTO
  вместо ручной сборки JSON.

### Feature

- `2a649c0` — run2#6: эндпоинт `GET /v1/limits` + тесты.
- `8fa647e` — run2#7: `history_count` в ответе `/healthz`.
- `32f0c33` — run2#8: кнопка «копировать фразу» в web UI.
- `cf0c8f0` — run2#9: таймаут запроса к DeepSeek настраивается через
  `DEEPSEEK_TIMEOUT_SEC` (`Config.kt`, `.env.example`).
- `2c8facc` — run2#10: заголовок `X-Request-Id` в ответах + request id в access-логе.

### Tests

- `aecfe4b` — run2#11: разбор строки `.env` вынесен в чистую функцию
  `parseDotEnvLine` + `ConfigTest`.
- `c4f2da5` — run2#12: raw-socket тест на 413 (тело больше лимита) + ассерты на 405.
- `c628b1b` — run2#13: санитайзер фразы — срез guillemets («») и схлопывание
  пробелов (`Motivator.kt`) + тесты.

### Docs

- `eb83fa1` — run2#14: секция Tests в README.
