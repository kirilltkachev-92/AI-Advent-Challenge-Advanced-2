#!/usr/bin/env bash
# Демо для видео: датасет для файнтюна классификатора тональности.
# Данные уже сгенерированы (./run.sh generate && ./run.sh prepare) и лежат в data/.
set -euo pipefail
cd "$(dirname "$0")"

echo "── 1. Формат примера: messages system → user → assistant ────"
# ensure_ascii=False — иначе кириллица уедет в \uXXXX и пример будет нечитаем
head -1 data/train.jsonl | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin), ensure_ascii=False, indent=2))'
echo

echo "── 2. Валидация train.jsonl и eval.jsonl ────────────────────"
./run.sh validate
echo

echo "── 3. Статистика выборок: 80/20, баланс классов ─────────────"
echo "real: $(wc -l < data/real.jsonl | tr -d ' ') | synthetic: $(wc -l < data/synthetic.jsonl | tr -d ' ') | train: $(wc -l < data/train.jsonl | tr -d ' ') | eval: $(wc -l < data/eval.jsonl | tr -d ' ')"
echo

echo "── 4. Baseline: базовая модель без файнтюна (точка отсчёта) ─"
if [[ -f output/baseline.md ]]; then
  cat output/baseline.md
else
  ./run.sh baseline
fi
echo

echo "── 5. Клиент файнтюна: dry-run плана запросов (не запускаем) ─"
./run.sh finetune
