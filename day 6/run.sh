#!/usr/bin/env bash
# ./run.sh <command>
#   generate  — сгенерировать синтетические примеры через DeepSeek
#   prepare   — очистка (дубли/пустые/длина) + split train/eval 80/20
#   validate  — валидация JSONL: JSON, три роли, непустой content
#   baseline  — 10 примеров из eval через базовую модель (без файнтюна)
#   finetune  — клиент файнтюна OpenAI (upload → job → poll); по умолчанию dry-run
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

MODE="${1:-validate}"
shift || true
mkdir -p output
./gradlew run -q --console=plain --args="$MODE $*" 2>&1 | tee "output/_run.log"
