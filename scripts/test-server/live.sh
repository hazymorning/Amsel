#!/usr/bin/env bash
# The live tests against Music Assistant <tag>, as CI runs them: a fresh server in Docker with the
# test library, the fake speakers Kitchen and Living Room, then the client, seeks on a speaker and
# the whole app's first-run journey. The server and the speakers are gone afterwards.
#
# Usage: live.sh <image-tag>      (NETWORK=host where Docker has no bridge network, see start.sh)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TAG="${1:?usage: live.sh <image-tag>}"

speakers=()
finish() {
  for pid in "${speakers[@]}"; do kill "$pid" 2>/dev/null || true; done
  "$HERE/stop.sh" "$TAG" || true
}
trap finish EXIT

URL=$("$HERE/start.sh" "$TAG" | tail -1)
TOKEN=$(python3 "$HERE/setup.py" --url "$URL")
if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  echo "::add-mask::$TOKEN"
fi
export MA_URL="$URL" MA_TOKEN="$TOKEN"

logs="$ROOT/build/test-server"
mkdir -p "$logs"
python3 "$HERE/fake_player.py" --name Kitchen > "$logs/kitchen.log" 2>&1 &
speakers+=($!)
python3 "$HERE/fake_player.py" --name "Living Room" --id amsel-fake-living-room > "$logs/living.log" 2>&1 &
speakers+=($!)

# Nothing tells Gradle which server answered, so these never count as done before or come from a cache.
cd "$ROOT"
./gradlew :api:test --tests '*LiveServerTest*' --rerun
./gradlew :playback:testDebugUnitTest --tests '*LiveSeekTest*' --rerun
./gradlew :app:testDebugUnitTest --tests '*FirstRunJourneyTest*' --rerun
