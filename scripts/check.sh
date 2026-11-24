#!/usr/bin/env bash
# Usage: scripts/check.sh [lint|tests|budgets|apk|live:<tag> ...]; each part is one job in ci.yml.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ $# -eq 0 ]]; then
  # Keep in step with the live-server matrix in ci.yml.
  set -- lint tests budgets apk live:2.10.9 live:2.11.0b7
fi

failed=()
for part in "$@"; do
  echo "== $part" >&2
  case "$part" in
    lint) ./gradlew ktlintCheck lintDebug ;;
    tests) ./gradlew testDebugUnitTest test :sendspin:testAndroidHostTest verifyRoborazziDebug ;;
    budgets) ./gradlew :app:checkBudgets ;;
    apk) ./gradlew :app:assembleDebug ;;
    live:?*) scripts/test-server/live.sh "${part#live:}" ;;
    *) echo "No such part: $part" >&2; false ;;
  esac || failed+=("$part")
done

if ((${#failed[@]})); then
  echo "Failed: ${failed[*]}" >&2
  exit 1
fi
echo "All passed: $*" >&2
