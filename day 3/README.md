# День 3. Testing

Два уровня тестирования для сервиса-мотиватора (day 1) + встройка в flow.

## Уровень 1 — код (unit/integration)

Агент одним промптом сам нашёл непокрытые модули day 1 и написал тесты
(зелёные с первого прогона, без сети — DeepSeek подменён фейком через
seam `fun interface ChatClient`):

- `day 1/src/test/kotlin/HistoryStoreTest.kt` — кольцо истории: вытеснение,
  порядок, `capacity <= 0`, конкурентные add, clear;
- `day 1/src/test/kotlin/MotivatorTest.kt` — доменная логика на фейке;
- `day 1/src/test/kotlin/HttpApiTest.kt` — интеграция: живой HttpServer на
  случайном порту, все ветви обороны (405/413/400/502/200), UI-маршрут, DELETE.

Запуск: `cd "day 1" && ./gradlew test`. Сейчас: **33/33**.

## Уровень 2 — UI-smoke

- Сценарии текстом: `smoke-scenarios.md` (v2, 5 сценариев S1–S5).
- Исполняемая форма: `smoke/run_smoke.py` — реальный Chrome (Playwright,
  headless), скриншот на каждом шаге → `screenshots/`, отчёт PASS/FAIL с
  указанием сломавшегося шага → `smoke-report-<tag>.md`.
- Запуск: поднять сервис (`cd "day 1" && ./run.sh`), затем
  `cd "day 3/smoke" && python3 run_smoke.py --tag <метка>`.
- Требование: `python3 -m pip install playwright` (браузер не качается —
  используется установленный Chrome, `channel="chrome"`).

Прогоны: `smoke-report-v1.md` (3/4 — S4 нашёл реальный баг UI),
`smoke-report-v1-rerun.md` (4/4 после фикса), `smoke-report-v2.md`
(5/5 после фичи «очистка истории»), `smoke-report-pr-2026-07-23.md` (5/5).

## Встройка в flow

Скилл `.claude/skills/post-pr-check/SKILL.md`: после PR — уровень 1 +
уровень 2 + единый отчёт `pr-check-report.md` (вердикт MERGE OK / НУЖНЫ
ПРАВКИ; падения уходят профилю bug-fix). Сценарий «задеплоил фичу» встроен
шагом 4: сначала обнови `smoke-scenarios.md` и раннер, потом прогон.

Полный ход дня и находки — `REPORT.md`.
