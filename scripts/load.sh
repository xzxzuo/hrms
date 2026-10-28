#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p results
if [ ! -f .env ]; then echo 'Run scripts/start.sh first' >&2; exit 1; fi
# .env is local project configuration; this script never prints it.
set -a
. ./.env
set +a
mode=${1:-cached}
docker run --rm -i --add-host=host.docker.internal:host-gateway -e MODE="$mode" -e DEMO_PASSWORD -e BASE_URL="${BASE_URL:-http://host.docker.internal:8080}" -v "$PWD/scripts:/scripts:ro" -v "$PWD/results:/results" grafana/k6:1.3.0 run /scripts/load.js
