#!/usr/bin/env bash
# Usage: start.sh <image-tag> [library-dir]   (env: PORT, NAME, NETWORK=host, PULL=1)
# Only the base URL goes to stdout, once /info reports "running". /data is an anonymous volume,
# so stop.sh removes it with the container.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAG="${1:?usage: start.sh <image-tag> [library-dir]}"
LIB="${2:-$HERE/build/test-library}"
PORT="${PORT:-8095}"
NAME="${NAME:-amsel-ma-$TAG}"
IMAGE="ghcr.io/music-assistant/server:$TAG"

if [[ "${PULL:-0}" == "1" ]] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Pulling $IMAGE ..." >&2
  for try in 1 2 3; do
    docker pull -q "$IMAGE" >&2 && break
    if ((try == 3)); then exit 1; fi
    sleep $((try * 20))
  done
fi

if [[ ! -d "$LIB" ]]; then
  FFMPEG_IMAGE="$IMAGE" "$HERE/make-library.sh" "$LIB"
fi
LIB="$(cd "$LIB" && pwd)"

# A container left from an earlier run would keep its onboarded data.
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
