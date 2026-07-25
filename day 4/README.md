# День 4. Local Boost

Локальная LLM как код-ассистент: **Ollama + Continue.dev в Cursor** на
Apple M4 Pro / 24 GB.

## Связка

| Роль | Модель | Параметры |
|---|---|---|
| Чат / edit в IDE | `qwen2.5-coder:7b` (кастом `day-coder`) | t=0.2, top_p=0.9, ctx 16384 |
| Автокомплит (FIM) | `qwen2.5-coder:1.5b-base` | t=0.1, 256 ток. |
| Индекс кодовой базы | `nomic-embed-text` | — |

- **`Modelfile`** — правила дня 1 (CLAUDE.md) зашиты в SYSTEM локальной модели:
  `ollama create day-coder -f Modelfile`.
- **`continue-config.yaml`** — копия рабочего `~/.continue/config.yaml`: три
  чат-модели на выбор, автокомплит, эмбеддинги, правила проекта в `rules`,
  контекст-провайдеры (file/code/codebase/diff/terminal/problems) — какие файлы
  модель видит; ctx 16k для 7B и 8k для 14B (потолок под 24 GB без свопа).
- Расширение: `cursor --install-extension Continue.continue` (v2.0.0).

## Бенч против облака

Те же задачи, что решал облачный ассистент (Claude Code):

- **Задача A** = день 1: сервис-мотиватор одним промптом;
- **Задача B** = день 2: баг HISTORY_SIZE=0 → причина + минимальный фикс.

Прогон: `bench/run_bench.py` (метрики из Ollama API), оценка:
`bench/evaluate.py` — компиляция извлечённых файлов в песочнице (двумя
build-файлами: модельным и эталонным) + фокус-тесты семантики фикса.
Сырые ответы моделей: `bench/out/*.md`, метрики: `bench/out/metrics.json`,
вердикты: `bench/out/eval.json`.

Итоги и таблица сравнения — `REPORT.md`.
