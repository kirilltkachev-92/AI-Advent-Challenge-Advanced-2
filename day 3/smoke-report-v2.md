# Smoke-прогон v2 — 2026-07-23 10:53

UI: http://127.0.0.1:8080 · Chrome (Playwright, headless) · скриншоты: `screenshots/v2-*.png`

| Сценарий | Вердикт | Шаги |
|---|---|---|
| S1. Страница открывается и готова к работе | ✅ PASS | 3 |
| S2. Генерация мотивации (happy path) | ✅ PASS | 4 |
| S3. Запись появляется в истории | ✅ PASS | 3 |
| S4. Ошибка валидации показывается пользователю | ✅ PASS | 5 |
| S5. Очистка истории (фича v2) | ✅ PASS | 5 |

## S1. Страница открывается и готова к работе — PASS

- 1. Открыть / → `screenshots/v2-S1-step1.png`
- 2. Форма на месте → `screenshots/v2-S1-step2.png`
- 3. Ошибок нет → `screenshots/v2-S1-step3.png`

## S2. Генерация мотивации (happy path) — PASS

- 1. Открыть / → `screenshots/v2-S2-step1.png`
- 2. Ввести задачу → `screenshots/v2-S2-step2.png`
- 3. Нажать «Мотивировать» → `screenshots/v2-S2-step3.png`
- 4. Фраза получена, ошибок нет → `screenshots/v2-S2-step4.png`

## S3. Запись появляется в истории — PASS

- 1. Запись из S2 в истории → `screenshots/v2-S3-step1.png`
- 2. Перезагрузить страницу → `screenshots/v2-S3-step2.png`
- 3. Запись пережила F5 (живёт на сервере) → `screenshots/v2-S3-step3.png`

## S4. Ошибка валидации показывается пользователю — PASS

- 1. Открыть / → `screenshots/v2-S4-step1.png`
- 2. Пустая задача → `screenshots/v2-S4-step2.png`
- 3. Нажать «Мотивировать» → `screenshots/v2-S4-step3.png`
- 4. Ошибка показана человеку → `screenshots/v2-S4-step4.png`
- 5. Сервис жив: повторная генерация работает → `screenshots/v2-S4-step5.png`

## S5. Очистка истории (фича v2) — PASS

- 1. Открыть / → `screenshots/v2-S5-step1.png`
- 2. Кнопка очистки видна → `screenshots/v2-S5-step2.png`
- 3. Нажать «Очистить историю» → `screenshots/v2-S5-step3.png`
- 4. История пуста, ошибок нет → `screenshots/v2-S5-step4.png`
- 5. F5: пусто и после перезагрузки → `screenshots/v2-S5-step5.png`
