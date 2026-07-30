# День 6. DataSet: датасет для файнтюна классификатора тональности

Готовим датасет для файнтюна модели под узкую задачу: классификация тональности
русскоязычных отзывов на мобильные приложения (банки, доставка, транспорт, маркетплейсы).
Ответ модели — строго одно слово: `позитивный`, `нейтральный` или `негативный`.
День покрывает весь конвейер «до нажатия кнопки»: сбор данных (реальные + синтетика),
очистка и split, валидация формата, baseline-замер базовой модели и готовый клиент
файнтюна OpenAI — который по условию дня **не запускается** (dry-run по умолчанию).

## Как устроено

```
RealExamples (12 ручных)        DeepSeek jsonMode (44 синтетики, батчи по 10,
        │                        контроль баланса классов + дедуп по ключу)
        ▼                                │
  data/real.jsonl              data/synthetic.jsonl        ──►  data/raw.jsonl (объединение)
        └──────────────┬───────────────┘
                       ▼  DatasetPreparer: дубли/пустые/<15/>600/метки,
                          shuffle Random(42), стратифицированный split 80/20
        data/train.jsonl (44)      data/eval.jsonl (12)
                       │                   │
     DatasetValidator (роли, метки,        │ первые 10 → BaselineRunner
     единый system, sealed-результат)      │ (локальная qwen2.5:14b через ollama)
                       │                   ▼
                       │            output/baseline.md (accuracy, format-compliance,
                       ▼             критерии улучшения после файнтюна)
     FineTuneClient: upload → job → poll (dry-run; --go для реального запуска)
```

- **Отклонение от шаблона марафона**: `data/*.jsonl` и `output/baseline.md` — deliverable
  этого дня, поэтому коммитятся сознательно; в `.gitignore` остаётся только `output/_run.log`.
- **Формат** — fine-tune OpenAI: каждая строка JSONL — `{"messages": [system, user, assistant]}`,
  system одинаков во всех примерах, assistant — эталонная метка одним словом.
- **`TrainingExample`** — DTO строки + каноничный ключ дедупа (lowercase, без пунктуации,
  схлопнутые пробелы).
- **`DatasetGenerator`** — синтетика через DeepSeek в jsonMode: в каждый промпт передаются
  уже имеющиеся отзывы (анти-дубли) и дефицит по классам (баланс сходится к равному);
  ответам модели не доверяем — метки, длина и дубли перепроверяются кодом.
- **`BaselineRunner`** — точка отсчёта: та же system-инструкция, temperature 0.0.
  **Решение** (по UPD дня 6: OpenAI закрыл self-serve файнтюн → baseline на локальной
  модели): выбор модели — `OPENAI_API_KEY` задан → `gpt-4o-mini` (явный опт-ин);
  иначе ollama доступен → **локальная `qwen2.5:14b`** (`OllamaClient`, OpenAI-совместимый
  `/v1/chat/completions` без ключа); иначе fallback `deepseek-v4-flash`.
- **`FineTuneClient`** — оркестрация Files API (multipart руками) + Fine-tuning API.
  По умолчанию dry-run: печатает пошагово запросы и тела, ничего не отправляя.
  Реальный запуск — только `./run.sh finetune --go` при наличии `OPENAI_API_KEY`.

### Результаты этого прогона

| | всего | train | eval |
|---|---|---|---|
| примеров | 56 (12 реальных + 44 синтетики) | 44 | 12 |
| баланс (поз/нейтр/нег) | 19/19/18 | 15/15/14 | 4/4/4 |

Baseline локальной `qwen2.5:14b` (ollama) на 10 примерах eval: **accuracy 7/10**,
format-compliance **8/10** — две ошибки формата («Позитивный» с заглавной) и одна
ошибка метки («нейтральный» → «позитивный»). Хорошая точка отсчёта для файнтюна:
есть что чинить и по формату, и по точности. Полная таблица и критерии —
`output/baseline.md`. (Предыдущий замер на облачной `deepseek-v4-flash`: 8/10 и 10/10.)

## Запуск

```bash
cp .env.example .env        # вписать DEEPSEEK_API_KEY (OPENAI_API_KEY — опционально)

./run.sh generate           # real.jsonl + synthetic.jsonl + raw.jsonl (DeepSeek)
./run.sh prepare            # очистка + split 80/20 → train.jsonl, eval.jsonl
./run.sh validate           # валидация обеих выборок (exit 1 при провале)
./run.sh baseline           # 10 примеров eval через базовую модель → output/baseline.md
./run.sh finetune           # dry-run плана файнтюна; реальный запуск: finetune --go
./demo.sh                   # демо для видео: пример строки → validate → baseline → dry-run
```

| Переменная | Дефолт | Зачем |
|---|---|---|
| `DEEPSEEK_API_KEY` | — (обязателен) | генерация синтетики, fallback-baseline |
| `DEEPSEEK_MODEL` | `deepseek-v4-flash` | модель генерации/fallback-baseline на DeepSeek |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | локальный ollama для baseline (UPD дня 6) |
| `LOCAL_MODEL` | `qwen2.5:14b` | локальная модель baseline через ollama |
| `OPENAI_API_KEY` | — (опционален) | baseline на gpt-4o-mini + реальный файнтюн |
| `OPENAI_BASE_MODEL` | `gpt-4o-mini` | базовая модель baseline при наличии ключа |
| `FINETUNE_MODEL` | `gpt-4o-mini-2024-07-18` | модель, на которой создаётся fine-tuning job |

## Чеклист задания

- [x] Задача файнтюна выбрана: классификация тональности отзывов, ответ ровно одним словом.
- [x] Датасет ≥ 50 примеров: 56 в `data/raw.jsonl` (train 44 + eval 12).
- [x] Формат messages (system/user/assistant), единый system во всех примерах.
- [x] ≥ 20% реальных данных: 12/56 = 21% ручных отзывов (`RealExamples`, `data/real.jsonl`).
- [x] Подготовка: очистка (дубли по каноничному ключу, пустые, длина, метки) с отчётом причин.
- [x] Split 80/20 стратифицированный, детерминированный (Random(42)).
- [x] Валидатор JSONL: роли по порядку, непустой content, метки, единый system; sealed `ValidationResult`, ненулевой exit code.
- [x] Baseline на 10 примерах eval + критерии улучшения — `output/baseline.md` (локальная qwen2.5:14b: accuracy 7/10, format 8/10).
- [x] UPD дня 6 учтён: baseline снят на локальной модели через ollama (OpenAI закрыл self-serve файнтюн).
- [x] Клиент файнтюна подготовлен (upload → job → poll), но НЕ запущен: dry-run по умолчанию, реальный запуск только `--go` + ключ.
