#!/usr/bin/env bash
# Start a throwaway Music Assistant server in Docker for tests.
#
# Usage: start.sh <image-tag> [library-dir]
#   image-tag    tag of ghcr.io/music-assistant/server, e.g. 2.10.9, 2.11.0b7, latest, beta
#   library-dir  music folder to mount read-only at /media (default: build/test-library,
#                generated with make-library.sh when missing)
#
# Environment:
#   PORT=8095        host port to publish (ignored with NETWORK=host)
#   NAME=...         container name (default amsel-ma-<tag>)
#   NETWORK=host     use host networking instead of publishing PORT
#   PULL=1           docker pull the image first even if it is present
#
# Prints the base URL (http://localhost:PORT) on stdout once /info reports status "running".
# The /data volume is an anonymous Docker volume: stop.sh removes it with the container.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAG="${1:?usage: start.sh <image-tag> [library-dir]}"
LIB="${2:-$HERE/build/test-library}"
PORT="${PORT:-8095}"
NAME="${NAME:-amsel-ma-$TAG}"
IMAGE="ghcr.io/music-assistant/server:$TAG"

if [[ "${PULL:-0}" == "1" ]] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Pulling $IMAGE ..." >&2
  docker pull -q "$IMAGE" >&2
fi

if [[ ! -d "$LIB" ]]; then
  FFMPEG_IMAGE="$IMAGE" "$HERE/make-library.sh" "$LIB"
fi
LIB="$(cd "$LIB" && pwd)"

# a stale container from an earlier run would keep its onboarded data: start clean
docker rm -f -v "$NAME" >/dev/null 2>&1 || true

NET_ARGS=(-p "$PORT:8095")
if [[ "${NETWORK:-}" == "host" ]]; then
  NET_ARGS=(--network host)
  PORT=8095
fi

docker run -d --name "$NAME" \
  "${NET_ARGS[@]}" \
  -v /data \
  -v "$LIB:/media:ro" \
  -e TZ=UTC \
  "$IMAGE" >/dev/null

echo "Started $NAME from $IMAGE ($(docker image inspect "$IMAGE" --format '{{index .Config.Labels "org.opencontainers.image.version"}}'))" >&2

URL="http://localhost:$PORT"
for _ in $(seq 1 180); do
  if info="$(curl -fsS "$URL/info" 2>/dev/null)" && [[ "$info" == *'"status":"running"'* || "$info" == *'"status": "running"'* ]]; then
    echo "Server ready: $info" >&2
    echo "$URL"
    exit 0
  fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" != "true" ]]; then
    echo "Container $NAME exited; last log lines:" >&2
    docker logs --tail 50 "$NAME" >&2 || true
    exit 1
  fi
  sleep 1
done
echo "Timed out waiting for $URL/info; last log lines:" >&2
docker logs --tail 50 "$NAME" >&2 || true
exit 1
