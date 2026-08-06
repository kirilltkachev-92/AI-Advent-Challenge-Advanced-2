#!/usr/bin/env bash
# ./run.sh <command>
#   run             — все атаки каталога × три режима обороны + output/report.md, transcript.md
#   one <id>        — одна атака подробно во всех режимах (id: own-scope, own-extract, …)
#   catalog         — коллекция атак с классификацией, офлайн, без ключа
#   guard "<текст>" — детерминированные эшелоны (input validation + output guard), офлайн
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
