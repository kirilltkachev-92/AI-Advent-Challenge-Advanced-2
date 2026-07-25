=== FILE: demo.sh ===
#!/usr/bin/env bash
# Демо для видео: сервис-мотиватор. Предполагает уже запущенный ./run.sh.
set -euo pipefail

BASE="http://127.0.0.1:${PORT:-8080}"

step() { echo; echo "── $1 ──"; }

check_status() {
  local expected=$1
  local response=$(curl -s -o /dev/null -w "%{http_code}" "$@")
  if [ "$response" -ne "$expected" ]; then
    echo "Ошибка: Ожидался HTTP-$expected, но получил $response"
    exit 1
  fi
}

step "1. GET /healthz — сервис жив"
check_status 200 "$BASE/healthz"

step "2. POST /v1/motivate — мотивация для задачи"
check_status 200 "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Написать миграцию базы на 40 таблиц до пятницы"}'

step "3. POST /v1/motivate — вторая задача"
check_status 200 "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Разгрести 200 непрочитанных code review"}'

step "4. POST /v1/motivate — невалидное тело → 400"
check_status 400 "$BASE/v1/motivate" -d 'not json'

step "5. GET /v1/motivate — не тот метод → 405"
check_status 405 "$BASE/v1/motivate"

step "6. GET /v1/history — последние запросы и ответы"
check_status 200 "$BASE/v1/history"
=== END ===