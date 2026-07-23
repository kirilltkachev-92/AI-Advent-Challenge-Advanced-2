#!/usr/bin/env bash
# Демо для видео: сервис-мотиватор. Предполагает уже запущенный ./run.sh.
set -euo pipefail

BASE="http://127.0.0.1:${PORT:-8080}"

step() { echo; echo "── $1 ──"; }

step "1. GET /healthz — сервис жив"
curl -s "$BASE/healthz" | python3 -m json.tool

step "2. POST /v1/motivate — мотивация для задачи"
curl -s -X POST "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Написать миграцию базы на 40 таблиц до пятницы"}' | python3 -m json.tool

step "3. POST /v1/motivate — вторая задача"
curl -s -X POST "$BASE/v1/motivate" \
  -H 'Content-Type: application/json' \
  -d '{"task": "Разгрести 200 непрочитанных code review"}' | python3 -m json.tool

step "4. POST /v1/motivate — невалидное тело → 400"
curl -s -X POST "$BASE/v1/motivate" -d 'not json' | python3 -m json.tool

step "5. GET /v1/motivate — не тот метод → 405"
curl -s "$BASE/v1/motivate" | python3 -m json.tool

step "6. GET /v1/history — последние запросы и ответы"
curl -s "$BASE/v1/history" | python3 -m json.tool
