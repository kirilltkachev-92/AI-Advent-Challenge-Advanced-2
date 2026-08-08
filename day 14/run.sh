#!/usr/bin/env bash
# ./run.sh — execution loop с security step (встроенный gateway на 8014)
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
# Сабкоманда report только печатает отчёт — не перетирает лог реального прогона.
if [[ "${1:-}" == "report" ]]; then
  ./gradlew run -q --console=plain --args="$*"
elif [[ $# -gt 0 ]]; then
  ./gradlew run -q --console=plain --args="$*" 2>&1 | tee "output/_run.log"
else
  ./gradlew run -q --console=plain 2>&1 | tee "output/_run.log"
fi
