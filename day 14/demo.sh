#!/usr/bin/env bash
# Демо для видео: execution loop с security step поверх встроенного LLM Gateway.
# Сам запускает ./run.sh (полный прогон 3 задач) — отдельный сервер не нужен.
set -euo pipefail
cd "$(dirname "$0")"

step() { echo; echo "── $1 ──"; }

step "1. Закопанные фейковые секреты в workspace-template (уйдут контекстом в LLM)"
grep -n -E "sk-|@example.com|\+7 900|4111" workspace-template/src/main/kotlin/*.kt | sed 's/^/  /'

step "2. Полный прогон лупа: 3 провокационные задачи через шлюз (mask mode)"
./run.sh

step "3. Хвост output/audit.jsonl — что поймал шлюз (фрагменты уже замаскированы)"
tail -3 output/audit.jsonl | while IFS= read -r line; do
  if command -v python3 >/dev/null; then
    python3 -m json.tool --no-ensure-ascii <<<"$line"
  else
    echo "$line"
  fi
done

step "4. Коммиты лупа в output/workspace"
git -C output/workspace log --oneline

step "5. Начало отчёта output/report.md"
head -25 output/report.md

echo
echo "Демо пройдено."
