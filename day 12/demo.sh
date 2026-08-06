#!/usr/bin/env bash
# Демо для видео: indirect prompt injection, действия агента и три защитных слоя (day 12).
# Шаги 1–3 офлайн (ключ не нужен), шаги 4–6 гоняют по 3 вызова LLM каждый,
# шаг 7 показывает закешированный output/report.md (иначе запускает полный прогон).
# Подписи шагов намеренно не утверждают исход: модель отвечает вживую, и на видео
# подпись не должна расходиться с тем, что видно в выводе.
# DEMO_PAUSE=<сек> — пауза после каждого шага (для записи видео; по умолчанию 0).
set -euo pipefail
cd "$(dirname "$0")"

pause() { sleep "${DEMO_PAUSE:-0}"; }

echo "── 1. Каталог: 4 сценария, техники сокрытия и два стиля закладки (офлайн) ─"
./run.sh catalog > /dev/null
sed -n '1,14p' output/catalog.md
echo
pause

echo "── 2. Эшелон 1: закладка есть в данных, но глазом её не видно ─────────────"
# Слева — то, что видит человек, справа — то, что уходило бы в модель.
./run.sh sanitize inbox
echo
pause

echo "── 3. Эшелон 1 не режет видимое: контрольная фикстура стилей (офлайн) ─────"
# font-size:16px и opacity:0.85 обязаны выжить, белое по белому и display:none — нет.
./run.sh sanitize style-fixture
echo
pause

echo "── 4. Письмо со скрытой инструкцией: смотрим, запросит ли агент действие ──"
# Инструменты фейковые: send_mail ничего не отправляет, домены — *.example.
./run.sh one mail-summary
echo
pause

echo "── 5. Вектор подмены факта: 16,0% с сайта ЦБ против 4,5% с агрегатора ─────"
./run.sh one web-search
echo
pause

echo "── 6. Реальный кейс Copilot: закладка в комментарии кода репозитория ──────"
./run.sh one repo-copilot
echo
pause

echo "── 7. Итоги полного прогона: частоты пробоя, действия и польза ────────────"
if [[ -f output/report.md ]]; then
  cat output/report.md
else
  ./run.sh run
  cat output/report.md
fi
echo
pause

echo "── 8. Главное: стиль закладки решает больше, чем техника сокрытия ─────────"
sed -n '/^## Стиль закладки/,/^## Что вырезал/p' output/report.md
sed -n '/^## Вывод/,$p' output/report.md
