#!/usr/bin/env bash
# Демо для видео: LLM Gateway со стражами. Предполагает уже запущенный ./run.sh.
# Шаги 2 и 4 ходят в реальный DeepSeek — нужен DEEPSEEK_API_KEY в day 13/.env.
set -euo pipefail

BASE="http://127.0.0.1:${PORT:-8013}"

step() { echo; echo "── $1 ──"; }

# request <ожидаемый HTTP-код> <аргументы curl...>
# Выполняет запрос, сверяет фактический код с ожидаемым, печатает тело как JSON.
request() {
  local expected="$1"; shift
  local response code body
  if ! response=$(curl -s -w $'\n%{http_code}' "$@"); then
    echo "FAIL: сервис недоступен (curl $*). Запущен ли ./run.sh?" >&2
    exit 1
  fi
  code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "$code" != "$expected" ]]; then
    echo "FAIL: ожидали HTTP $expected, получили HTTP $code" >&2
    echo "Тело ответа: $body" >&2
    exit 1
  fi
  echo "HTTP $code (как и ожидалось)"
  if ! python3 -m json.tool --no-ensure-ascii <<<"$body"; then
    echo "FAIL: тело ответа — не JSON: $body" >&2
    exit 1
  fi
}

step "1. GET /healthz — шлюз жив"
request 200 "$BASE/healthz"

step "2. Чистый промпт → уходит в реальный LLM, в ответе usage и стоимость"
request 200 -X POST "$BASE/v1/chat" \
  -H 'Content-Type: application/json' \
  -d '{"prompt": "Одним предложением: зачем нужен LLM-шлюз перед моделью?"}'

step "3. Промпт с sk-ключом, mode=block → заблокирован, в LLM НИЧЕГО не ушло"
request 200 -X POST "$BASE/v1/chat" \
  -H 'Content-Type: application/json' \
  -d '{"prompt": "Проверь мой ключ sk-demo1234567890abcd, он рабочий?", "mode": "block"}'

step "4. Тот же промпт, mode=mask → в LLM уходит [REDACTED_API_KEY]"
request 200 -X POST "$BASE/v1/chat" \
  -H 'Content-Type: application/json' \
  -d '{"prompt": "Проверь мой ключ sk-demo1234567890abcd, он рабочий?", "mode": "mask"}'

step "5. Номер карты (валидный Luhn) → заблокирован"
request 200 -X POST "$BASE/v1/chat" \
  -H 'Content-Type: application/json' \
  -d '{"prompt": "Оплати картой 4111 1111 1111 1111 подписку", "mode": "block"}'

step "6. Rate limit: шлём запросы, пока не получим 429"
GOT_429=""
for i in $(seq 1 15); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/chat" \
    -H 'Content-Type: application/json' \
    -d '{"prompt": "Ключ sk-flood1234567890 для теста лимита", "mode": "block"}')
  echo "запрос $i → HTTP $code"
  if [[ "$code" == "429" ]]; then GOT_429="yes"; break; fi
done
if [[ -z "$GOT_429" ]]; then
  echo "FAIL: за 15 запросов так и не получили 429" >&2
  exit 1
fi
echo "Rate limit сработал: 429 получен."

step "7. GET /v1/audit — хвост аудита с вердиктами и стоимостью сессии"
request 200 "$BASE/v1/audit?limit=5"

echo
echo "Демо пройдено: все шаги вернули ожидаемые HTTP-коды."
