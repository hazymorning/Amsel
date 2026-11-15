#!/usr/bin/env bash
# Stop and remove a test server started by start.sh, including its /data volume.
# Usage: stop.sh <image-tag>     (or NAME=<container> stop.sh)
set -euo pipefail
NAME="${NAME:-amsel-ma-${1:?usage: stop.sh <image-tag>}}"
docker rm -f -v "$NAME" >/dev/null 2>&1 && echo "Removed $NAME" >&2 || echo "$NAME was not running" >&2
