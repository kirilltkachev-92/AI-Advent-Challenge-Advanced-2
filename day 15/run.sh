#!/usr/bin/env bash
# ./run.sh — боевой red-team стенд дня 15.
#   ./run.sh                      # serve: публичный AgentApi + внутренний шлюз (до Ctrl+C)
#   ./run.sh serve
#   ./run.sh attack [url] [phase] # red-team по цели (дефолт свой localhost), phase=before|after
#   ./run.sh selfrun [id]         # батч 3 провокационных задач → output/report.md
#   ./run.sh report               # напечатать последний selfrun-отчёт
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
mkdir -p output

# report/attack только печатают/пишут свои артефакты — не перетирают лог serve.
if [[ "${1:-}" == "report" || "${1:-}" == "attack" ]]; then
  ./gradlew run -q --console=plain --args="$*"
else
  ./gradlew run -q --console=plain --args="${*:-serve}" 2>&1 | tee "output/_run.log"
fi
