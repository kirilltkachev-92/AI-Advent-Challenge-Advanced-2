#!/usr/bin/env bash
# ./run.sh <command>
#   run           — 10 кейсов × (монолит A + конвейер B) + output/report.md
#   one "<текст>" — оба варианта на одном тексте, подробный вывод + кодовая проверка
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
