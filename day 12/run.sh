#!/usr/bin/env bash
# ./run.sh <command>
#   run [--repeat N]              — матрица 4 сценария × 3 режима × N прогонов (дефолт 3)
#                                   + сравнение стилей закладки в naive;
#                                   пишет output/report.md, runs.md, transcript.md
#   one <id> [overt|covert]       — один сценарий подробно во всех режимах
#                                   (id: mail-summary, doc-analysis, web-search, repo-copilot)
#   catalog                       — сценарии, техники сокрытия и реальные кейсы, офлайн, без ключа
#   sanitize <srcId> [overt|covert] — что видит человек против того, что уходит в модель, офлайн
#                                   (id: inbox, q3-report, cbr-page, aggregator-page,
#                                    payment-form, style-fixture)
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; do
    if [[ -d "$candidate" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "Java 17 not found. Install with: brew install openjdk@17" >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

MODE="${1:-run}"
shift || true
mkdir -p output
./gradlew run -q --console=plain --args="$MODE $*" 2>&1 | tee "output/_run.log"
