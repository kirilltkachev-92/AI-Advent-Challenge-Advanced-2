#!/usr/bin/env bash
# Демо для видео: оценка уверенности и контроль качества инференса (day 7).
# Полный прогон 12 кейсов дорогой (~60 LLM-вызовов), поэтому шаг 3 показывает
# закешированный output/report.md, если он уже есть (иначе запускает прогон).
# DEMO_PAUSE=<сек> — пауза после каждого шага (для записи видео; по умолчанию 0).
set -euo pipefail
cd "$(dirname "$0")"

pause() { sleep "${DEMO_PAUSE:-0}"; }

echo "── 1. Корректное поручение: конвейер принимает с confidence ──"
./run.sh one "Переведи 5000 рублей Ивану Петрову"
echo
pause

echo "── 2. Шумный кейс: не поручение → REJECT, эскалация человеку ──"
./run.sh one "Какая погода в Москве?"
echo
pause

echo "── 3. Полный прогон 12 кейсов (или кешированный отчёт) ───────"
if [[ -f output/report.md ]]; then
  cat output/report.md
else
  ./run.sh run
  cat output/report.md
fi
echo
pause

echo "── 4. Итоги и цена контроля vs single-shot ────────────────────"
sed -n '/^## Итоги/,$p' output/report.md
