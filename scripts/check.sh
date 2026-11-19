#!/usr/bin/env bash
# CI's checks, run here as CI runs them. Each CI job runs one part; before a pull request all of them
# run here, so the pull request's CI passes the first time.
#
# Usage: scripts/check.sh [part ...]
#   lint          ktlint and Android lint
#   tests         the unit tests and the screenshots
#   budgets       permissions, dependencies and APK size
#   apk           the debug APK
#   live:<tag>    the live tests against Music Assistant <tag> in Docker (scripts/test-server/live.sh)
# Without parts, all of them, against both servers CI tests with. Every part runs; those that failed
# are named at the end.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ $# -eq 0 ]]; then
  # The servers of CI's live-server matrix.
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
