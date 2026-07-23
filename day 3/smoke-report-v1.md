# Smoke-прогон v1 — 2026-07-23 10:45

UI: http://127.0.0.1:8080 · Chrome (Playwright, headless) · скриншоты: `screenshots/v1-*.png`

| Сценарий | Вердикт | Шаги |
|---|---|---|
| S1. Страница открывается и готова к работе | ✅ PASS | 3 |
| S2. Генерация мотивации (happy path) | ✅ PASS | 4 |
| S3. Запись появляется в истории | ✅ PASS | 3 |
| S4. Ошибка валидации показывается пользователю | ❌ FAIL | 3 |

## S1. Страница открывается и готова к работе — PASS

- 1. Открыть / → `screenshots/v1-S1-step1.png`
- 2. Форма на месте → `screenshots/v1-S1-step2.png`
- 3. Ошибок нет → `screenshots/v1-S1-step3.png`

## S2. Генерация мотивации (happy path) — PASS

- 1. Открыть / → `screenshots/v1-S2-step1.png`
- 2. Ввести задачу → `screenshots/v1-S2-step2.png`
- 3. Нажать «Мотивировать» → `screenshots/v1-S2-step3.png`
- 4. Фраза получена, ошибок нет → `screenshots/v1-S2-step4.png`

## S3. Запись появляется в истории — PASS

- 1. Запись из S2 в истории → `screenshots/v1-S3-step1.png`
- 2. Перезагрузить страницу → `screenshots/v1-S3-step2.png`
- 3. Запись пережила F5 (живёт на сервере) → `screenshots/v1-S3-step3.png`

## S4. Ошибка валидации показывается пользователю — FAIL

- 1. Открыть / → `screenshots/v1-S4-step1.png`
- 2. Пустая задача → `screenshots/v1-S4-step2.png`
- 3. Нажать «Мотивировать» → `screenshots/v1-S4-step3.png`

**Где проблема:** упал шаг 4:        - unexpected value "empty"
Скриншот падения: `screenshots/v1-S4-FAIL.png`
