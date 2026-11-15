#!/usr/bin/env bash
# Re-record the WebSocket fixtures for one server version, from a clean server.
#
# Usage: record.sh <image-tag> <fixture-dir-name>
#   e.g. record.sh 2.10.9 2.10      record.sh 2.11.0b7 2.11
# Writes api/src/testFixtures/resources/fixtures/<fixture-dir-name>/*.json, then removes the server.
# Environment: PORT (default 8095).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TAG="${1:?usage: record.sh <image-tag> <fixture-dir-name>}"
DIR="${2:?usage: record.sh <image-tag> <fixture-dir-name>}"
OUT="$ROOT/api/src/testFixtures/resources/fixtures/$DIR"
export PORT="${PORT:-8095}"

trap '"$HERE/stop.sh" "$TAG"' EXIT
URL="$("$HERE/start.sh" "$TAG")"
TOKEN="$(python3 "$HERE/setup.py" --url "$URL")"
# A radio station needs a stream the server can reach: serve one library file on the
# container's own loopback, so nothing is exposed outside it.
docker exec -d "${NAME:-amsel-ma-$TAG}" python3 -m http.server 8777 --bind 127.0.0.1 \
  --directory "/media/Aurora Tone/Second Wind (2023)"
RADIO_URL="http://127.0.0.1:8777/01%20-%20Tailwind.mp3"
rm -rf "$OUT"
MA_TOKEN="$TOKEN" python3 "$HERE/record_fixtures.py" --url "$URL" --out "$OUT" --radio-url "$RADIO_URL"
