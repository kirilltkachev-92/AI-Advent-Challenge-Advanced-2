# Прогон профиля bug-fix (v1) — первая попытка, один промпт

**Промпт** (дословно, без уточнений):

> Баг: хотел отключить историю запросов — поставил HISTORY_SIZE=0 в "day 1/.env"
> (сейчас так и стоит). Теперь КАЖДЫЙ POST /v1/motivate отвечает 500
> {"error":{"code":"internal",...}}, хотя по логу видно, что DeepSeek успешно вернул
> фразу. Если убрать HISTORY_SIZE (дефолт 10) — всё работает. Ожидание:
> HISTORY_SIZE=0 просто отключает историю, /v1/motivate работает, /v1/history
> отдаёт пустой список.

**Ответ агента** (полностью):

Единственные вызовы `HistoryStore` — `HttpApi.kt:53` (snapshot) и `HttpApi.kt:96` (add), оба прогнаны живыми запросами. `.env` не трогал — `HISTORY_SIZE=0` там так и стоит и теперь работает как выключатель истории.

## Причина
`day 1/src/main/kotlin/HistoryStore.kt:20` — условие вытеснения `if (entries.size == capacity) entries.removeFirst()`. При `HISTORY_SIZE=0` capacity равен 0, и на пустой деке `0 == 0` истинно уже при первом `add()`, поэтому `removeFirst()` кидает `NoSuchElementException("ArrayDeque is empty.")`. `add()` вызывается в `HttpApi.kt:96` **после** успешного ответа DeepSeek (поэтому в логе фраза есть), исключение долетает до общего catch в `HttpApi.kt:116` → 500 `{"error":{"code":"internal","message":"ArrayDeque is empty."}}`.

## Что починил
`HistoryStore.kt`, метод `add` (+3 строки, минимальный дифф в корень причины):
- `if (capacity <= 0) return` — неположительная ёмкость = история отключена, запись не сохраняется;
- условие вытеснения `==` → `>=` — защита от того же сбоя при любой некорректной комбинации размера и ёмкости;
- обновил KDoc класса: контракт теперь явно описывает `capacity <= 0` как отключение истории.

Симптом (глушение исключения сверху) не лечил — убрана сама невозможная операция `removeFirst()` на пустой деке.

## Что проверил
- воспроизведение: `curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/v1/motivate -H 'Content-Type: application/json' -d '{"task":"write tests"}'` при `HISTORY_SIZE=0` → было `HTTP 500 {"error":{"code":"internal","message":"ArrayDeque is empty."}}`, стало `HTTP 200 {"task":"write tests","phrase":"...","model":"deepseek-chat"}`
- сборка: `./gradlew compileKotlin --console=plain` → BUILD_OK; `src/test` нет
- регрессия:
  - `GET /v1/history` при `HISTORY_SIZE=0` → `{"count":0,"entries":[]}`, HTTP 200 (ожидание пользователя выполнено);
  - невалидный `POST /v1/motivate` c `{}` → HTTP 400 `bad_request`;
  - `HISTORY_SIZE=2` (env-override, `.env` не менял): три POST → все 200, `GET /v1/history` → `count=2`, свежие первыми, старейшая вытеснена — включённая история и вытеснение не сломаны;
  - Grep по вызовам: `history.add` и `history.snapshot` используются только в `HttpApi.kt` — оба пути прогнаны.

**Вердикт: рабочий фикс с первого запуска.** 16 tool-вызовов, ~2,2 мин.
