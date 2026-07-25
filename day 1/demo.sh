#!/usr/bin/env bash
# Демо для видео: сервис-мотиватор. Предполагает уже запущенный ./run.sh.
# Каждый шаг проверяет ожидаемый HTTP-код; при расхождении — сообщение и exit 1.
set -euo pipefail

BASE="http://127.0.0.1:${PORT:-8080}"

step() { echo; echo "── $1 ──"; }

# req <ожидаемый HTTP-код> <curl-аргументы…> — запрос, проверка кода, печать JSON.
req() {
  local expect="$1"; shift
  local raw code body
  if ! raw=$(curl -s -w $'\n%{http_code}' "$@"); then
    echo "ОШИБКА: curl не смог выполнить запрос ($*). Сервис запущен (./run.sh)?" >&2
    exit 1
  fi
  code="${raw##*$'\n'}"
  body="${raw%$'\n'*}"
  if [[ "$code" != "$expect" ]]; then
    echo "ОШИБКА: ожидался HTTP $expect, получен HTTP $code. Тело ответа:" >&2
    printf '%s\n' "$body" >&2
    exit 1
  fi
  echo "HTTP $code"
  if ! printf '%s' "$body" | python3 -m json.tool 2>/dev/null; then
    echo "ОШИБКА: HTTP $code совпал, но тело ответа — не JSON:" >&2
    printf '%s\n' "$body" >&2
    exit 1
  fi
}

step "1. GET /healthz — сервис жив"
req 200 "$BASE/healthz"

step "2. POST /v1/motivate — мотивация для задачи"
req 200 -X POST "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Написать миграцию базы на 40 таблиц до пятницы"}'

step "3. POST /v1/motivate — вторая задача"
req 200 -X POST "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Разгрести 200 непрочитанных code review"}'

step "4. POST /v1/motivate — невалидное тело → 400"
req 400 -X POST "$BASE/v1/motivate" -d 'not json'

step "5. GET /v1/motivate — не тот метод → 405"
req 405 "$BASE/v1/motivate"

step "6. GET /v1/history — последние запросы и ответы"
req 200 "$BASE/v1/history"

echo
echo "Все шаги прошли: HTTP-коды совпали с ожидаемыми."
