#!/usr/bin/env bash
# Демо для видео: micro-model first — проверка перед LLM (day 10).
# Полный прогон 30 кейсов — до ~41 LLM-вызова, поэтому шаг 3 показывает
# закешированный output/report.md, если он уже есть (иначе запускает прогон).
# DEMO_PAUSE=<сек> — пауза после каждого шага (для записи видео; по умолчанию 0).
set -euo pipefail
cd "$(dirname "$0")"

pause() { sleep "${DEMO_PAUSE:-0}"; }

echo "── 1. Простой запрос: micro-model отвечает сама, 0 LLM-вызовов ──────"
./run.sh one "какой у меня баланс"
echo
pause

echo "── 2. Трудный запрос: UNSURE → fallback в большую LLM ───────────────"
./run.sh one "переведи маме тысячу и заблокируй карту потом"
echo
pause

echo "── 3. Полный прогон 30 кейсов: конвейер + all-LLM-базлайн ───────────"
if [[ -f output/report.md ]]; then
  cat output/report.md
else
  ./run.sh run
  cat output/report.md
fi
echo
pause

echo "── 4. Итоги: сколько закрыла micro, цена fallback vs all-LLM ────────"
sed -n '/^## Итоги/,$p' output/report.md
