# День 1. HTTP-сервис «мотиватор»

Маленький сервис на JDK-стеке: принимает описание задачи и возвращает короткую
мотивационную фразу, сгенерированную DeepSeek. Последние 10 обменов хранятся
в памяти и доступны отдельным эндпоинтом.

Порт **8080** — по условию дня (осознанное отступление от конвенции 8000+N
марафона); переопределяется переменной `PORT`.

## Как устроено

```
клиент ──POST /v1/motivate {"task": …}──▶ HttpApi ──▶ Motivator ──▶ DeepSeekClient ──▶ DeepSeek API
                                            │             │
                                            │             └──▶ HistoryStore (кольцо на 10 записей)
клиент ◀──{"task","phrase","model"}─────────┘
клиент ──GET /v1/history──▶ HttpApi ──▶ HistoryStore.snapshot() (свежие первыми)
клиент ──DELETE /v1/history▶ HttpApi ──▶ HistoryStore.clear() → {"cleared": true}
клиент ──GET /healthz────▶ HttpApi (статус, модель, аптайм, счётчик, history_count)
клиент ──GET /v1/limits──▶ HttpApi (max_task_chars, max_body_bytes, history_size, model)
браузер ──GET /──────────▶ HttpApi ──▶ WebUi.PAGE (одностраничный UI, text/html)
```

- `Config.kt` — env + `.env`, типизированные геттеры;
- `DeepSeekClient.kt` — /chat/completions руками на `java.net.http`, без SDK;
- `Motivator.kt` — промпт и генерация фразы (1–2 предложения, temperature 1.1);
- `HistoryStore.kt` — потокобезопасное кольцо последних `HISTORY_SIZE` (по умолчанию 10)
  записей в памяти; значение `<= 0` отключает историю (записи не сохраняются);
  `clear()` полностью очищает историю — снаружи это `DELETE /v1/history`
  (200 `{"cleared": true}`; прочие методы, кроме GET, — 405), в UI — кнопка
  «Очистить историю» (`#clearHistoryBtn`) рядом с заголовком истории;
- `HttpApi.kt` — транспорт на `com.sun.net.httpserver`: маршруты, оборона
  (405 → 413 до чтения тела → 400 → 502), access-лог, ошибки `{"error":{code,message}}`;
  каждому запросу назначается короткий id (8 hex): возвращается заголовком
  `X-Request-Id` во всех ответах (включая ошибки) и пишется в начале строки
  access-лога — `[a1b2c3d4] GET /healthz 200 ← 127.0.0.1 за 1 мс`;
- `WebUi.kt` — одностраничный веб-UI (GET `/`): самодостаточный HTML с инлайн CSS/JS,
  тёмная тема, без внешних ресурсов; fetch к `POST /v1/motivate` и `GET /v1/history`,
  ошибки API показываются текстом из `error.message`; кнопка «Скопировать» (`#copyBtn`)
  копирует фразу в буфер обмена и видна только при показанной фразе; стабильные id
  для автотестов — `taskInput`, `motivateBtn`, `phraseBox`, `copyBtn`, `errorBox`,
  `historyList`, `clearHistoryBtn` (записи — `li.history-item`);
- `Main.kt` — только wiring.

## Запуск

```bash
cp .env.example .env   # вписать DEEPSEEK_API_KEY
./run.sh               # сервис на http://127.0.0.1:8080, лог — output/_run.log
./demo.sh              # curl-сценарий демо (в соседнем терминале)
```

| Переменная         | Дефолт          | Зачем                                   |
|--------------------|-----------------|-----------------------------------------|
| `DEEPSEEK_API_KEY` | — (обязателен)  | ключ DeepSeek                           |
| `DEEPSEEK_MODEL`   | `deepseek-v4-flash` | модель генерации                        |
| `DEEPSEEK_TIMEOUT_SEC` | `60`        | таймаут запроса к DeepSeek, секунды     |
| `PORT`             | `8080`          | порт HTTP-сервиса                       |
| `BIND_HOST`        | `127.0.0.1`     | адрес бинда                             |
| `MAX_TASK_CHARS`   | `2000`          | потолок длины поля `task`               |
| `MAX_BODY_BYTES`   | `16384`         | потолок тела запроса (проверка до чтения) |
| `HISTORY_SIZE`     | `10`            | глубина истории в памяти; `<= 0` — история отключена (`/v1/history` отдаёт пустой список) |

## Тесты

```bash
./gradlew test    # 59 тестов (kotlin-test + JUnit 5), сеть не нужна
```

Что покрыто (`src/test/kotlin`):

- `ConfigTest` — чистый парсер строки `.env` (`Config.parseDotEnvLine`):
  комментарии, кавычки, пробелы, пустые значения; реальный `.env` не читается;
- `MotivatorTest` — доменная логика на фейке `ChatClient`: что уходит в LLM
  (системный промпт, temperature 1.1), чистка ответа (trim, схлопывание пробелов,
  снятие обрамляющих кавычек и «ёлочек»), исключения → `MotivationResult.Failed`;
- `HistoryStoreTest` — кольцевой буфер: вытеснение старых, порядок снимка,
  `clear()`, `capacity <= 0`, конкурентные `add`;
- `HttpApiTest` — интеграция: реальный `HttpServer` на случайном порту,
  DeepSeek подменён фейковым `ChatClient` (сеть не трогается); ветви обороны
  `/v1/motivate` (405 → 413 до чтения тела, через raw-socket → 400 → 502 → 200),
  `/healthz`, `/v1/history` (включая DELETE), `/v1/limits`, веб-UI на `GET /`
  (контракт стабильных id), `X-Request-Id` во всех ответах.

UI-smoke поверх живого сервиса — в дне 3: Playwright-раннер
[`day 3/smoke/run_smoke.py`](../day%203/smoke/run_smoke.py) (сервис поднять
заранее через `./run.sh`).

## Чеклист задания

- [x] `GET /healthz` — статус сервиса (status, модель, аптайм, счётчик запросов, history_count);
- [x] `POST /v1/motivate` — `{"task": "…"}` → мотивационная фраза через DeepSeek
      (`Motivator` + `DeepSeekClient`, протокол руками);
- [x] `GET /v1/history` — последние 10 запросов и ответов, только в памяти
      (`HistoryStore`, кольцо с вытеснением старых);
- [x] порт 8080 (дефолт в `Config.port()`, переопределяется `PORT`);
- [x] веб-UI на `GET /` — ввод задачи, кнопка «Мотивировать», фраза-результат и
      история с автообновлением (`WebUi.kt`); не-GET → 405, неизвестный путь → 404 JSON;
- [x] очистка истории — `DELETE /v1/history` → 200 `{"cleared": true}`
      (`HistoryStore.clear()`), в UI кнопка «Очистить историю» (`#clearHistoryBtn`)
      без confirm-диалогов; покрыто тестами в `HttpApiTest` и `HistoryStoreTest`;
- [x] кнопка «Скопировать» (`#copyBtn`) рядом с фразой — копирует фразу в буфер
      обмена через Clipboard API, появляется только когда фраза показана,
      ошибки — в `#errorBox`; id проверяется контрактным тестом в `HttpApiTest`;
- [x] `X-Request-Id` — короткий id на каждый запрос: заголовок во всех ответах
      (включая 404/405/400/502/500) и префикс строки access-лога; покрыто
      интеграционными тестами в `HttpApiTest`.
