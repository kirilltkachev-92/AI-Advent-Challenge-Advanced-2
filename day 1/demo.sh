#!/usr/bin/env bash
# Демо для видео: сервис-мотиватор. Предполагает уже запущенный ./run.sh.
set -euo pipefail

BASE="http://127.0.0.1:${PORT:-8080}"

step() { echo; echo "── $1 ──"; }

# request <ожидаемый HTTP-код> <аргументы curl...>
# Выполняет запрос, сверяет фактический код с ожидаемым, печатает тело как JSON.
# При недоступном сервисе, неверном коде или не-JSON теле — понятный FAIL и exit 1.
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
  if ! python3 -m json.tool <<<"$body"; then
    echo "FAIL: тело ответа — не JSON: $body" >&2
    exit 1
  fi
}

step "1. GET /healthz — сервис жив"
request 200 "$BASE/healthz"

step "2. POST /v1/motivate — мотивация для задачи"
request 200 -X POST "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Написать миграцию базы на 40 таблиц до пятницы"}'

step "3. POST /v1/motivate — вторая задача"
request 200 -X POST "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Разгрести 200 непрочитанных code review"}'

step "4. POST /v1/motivate — невалидное тело → 400"
request 400 -X POST "$BASE/v1/motivate" -d 'not json'

step "5. GET /v1/motivate — не тот метод → 405"
request 405 "$BASE/v1/motivate"

step "6. GET /v1/history — последние запросы и ответы"
request 200 "$BASE/v1/history"

echo
echo "Демо пройдено: все шаги вернули ожидаемые HTTP-коды."
