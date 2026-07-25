# CHANGELOG — мотиватор (day 1)

История изменений сервиса по git-истории (`git log -- "day 1"`), сгруппирована
по дням марафона. Каждая запись ссылается на реальный коммит.

## День 1 — рождение сервиса (`3bb44f8`)

`Day 1. Rules: project rules file (v2) + battle-tested generation diff`

- Первая версия сервиса целиком: `Config.kt` (env + `.env`), `DeepSeekClient.kt`
  (`java.net.http`, без SDK), `Motivator.kt` (промпт + генерация фразы),
  `HistoryStore.kt` (кольцо в памяти), `HttpApi.kt` (`com.sun.net.httpserver`:
  `/healthz`, `POST /v1/motivate`, `GET /v1/history`), `Main.kt` (wiring).
- Инфраструктура: Gradle wrapper, `run.sh`, `demo.sh`, `.env.example`,
  `.gitignore`, README, REPORT.
- Артефакты эксперимента с правилами: `report/gen1/` (генерация без правил v2)
  и `report/gen1-vs-gen2.diff`.

## День 2 — bug-fix HISTORY_SIZE + синхронизация доков (`d76ead2`)

`Day 2. Profiles: bug-fix, research, day-docs agent profiles`

- Bug-fix (найден первым прогоном профиля bug-fix): при `HISTORY_SIZE=0` каждый
  `/v1/motivate` падал с 500 — `HistoryStore.add` делал `removeFirst()` на
  пустом deque. Теперь `capacity <= 0` отключает историю (записи не
  сохраняются), сравнение размера — `>=` вместо `==`.
- README синхронизирован профилем day-docs: описание `HistoryStore` и строка
  `HISTORY_SIZE` в таблице env отражают поведение `<= 0`.

## День 3 — тесты, веб-UI, очистка истории (`fa276eb`)

`Day 3. Testing: unit/integration tests + UI smoke via Playwright + PR flow`

- Тесты (33/33 зелёные): `HistoryStoreTest`, `MotivatorTest`, `HttpApiTest`
  (интеграция на порту 0, `HttpApi.start(port)` принимает порт параметром).
- Seam для тестируемости: `fun interface ChatClient`; `DeepSeekClient` теперь
  его реализация, `Motivator` зависит от интерфейса — тесты без сети.
- Одностраничный веб-UI: `WebUi.kt`, `GET /` отдаёт HTML со стабильными id для
  автоматизации; неизвестные пути — 404 JSON.
- Очистка истории: `HistoryStore.clear()` + `DELETE /v1/history` →
  `{"cleared": true}`; smoke v1 поймал реальный баг UI (пустой ввод молча
  проглатывался) — исправлен через bug-fix профиль.

## День 5 — loop-run-1: 14 задач конвейером

### Bug-fix

- `b82e0dd` — loop#1: баннер при старте сообщает, что история отключена
  (`Main.kt`).
- `61d1866` — loop#2: время в истории UI форматируется как `HH:MM:SS`
  (`WebUi.kt`).
- `6d4c76b` — loop#3: `demo.sh` проверяет HTTP-коды на каждом шаге.

### Refactor

- `11a214f` — loop#4: sealed `MotivationResult` в домене (`Motivator.kt`),
  `HttpApi` разбирает результат, тесты обновлены.
- `65b908e` — loop#5: ответ `/v1/history` собирается через
  kotlinx.serialization DTO вместо ручной конкатенации (`HttpApi.kt`).

### Feature

- `57bee1d` — loop#6: эндпоинт `GET /v1/limits` + тесты + README.
- `a93deec` — loop#7: `history_count` в ответе `/healthz`
  (`HistoryStore.count()` + `HttpApi`).
- `9cd55a8` — loop#8: кнопка «копировать» в веб-UI (`WebUi.kt`).
- `441542b` — loop#9: env `DEEPSEEK_TIMEOUT_SEC` — настраиваемый таймаут
  DeepSeek (`Config.kt`, `DeepSeekClient.kt`, `.env.example`, README).
- `42ee82b` — loop#10: заголовок `X-Request-Id` в ответах + access-лог с id
  (`HttpApi.kt`) + тесты.

### Tests

- `0372f18` — loop#11: `parseDotEnvLine` выделена в чистую функцию, добавлен
  `ConfigTest` (92 строки тестов парсинга `.env`).
- `ba7fb9f` — loop#12: raw-socket тест на 413 (Content-Length больше потолка,
  тело не читается) + assertions на 405.
- `a7bbbb0` — loop#13: санитайзер фразы — «ёлочки» и схлопывание пробелов
  (`Motivator.kt`) + тесты.

### Docs

- `da1e551` — loop#14: секция Tests в README (как запускать, что покрыто).
