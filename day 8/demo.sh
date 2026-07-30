#!/usr/bin/env bash
# Демо для видео: роутинг между дешёвой и сильной моделью (day 8).
# Полный прогон 14 обращений — до ~28 LLM-вызовов, поэтому шаг 3 показывает
# закешированный output/report.md, если он уже есть (иначе запускает прогон).
# DEMO_PAUSE=<сек> — пауза после каждого шага (для записи видео; по умолчанию 0).
set -euo pipefail
cd "$(dirname "$0")"

pause() { sleep "${DEMO_PAUSE:-0}"; }

echo "── 1. Ясное обращение: остаётся на дешёвом flash ──────────────"
./run.sh one "Карта заблокировалась после трёх неверных пинов"
echo
pause

echo "── 2. Мутное обращение: эвристика эскалирует на pro ───────────"
./run.sh one "Всё сломалось, помогите"
echo
pause

echo "── 3. Полный прогон 14 обращений (или кешированный отчёт) ─────"
if [[ -f output/report.md ]]; then
  cat output/report.md
else
  ./run.sh run
  cat output/report.md
fi
echo
pause

echo "── 4. Итоги: кто остался на flash, кто ушёл на pro, экономия ──"
sed -n '/^## Итоги/,$p' output/report.md
