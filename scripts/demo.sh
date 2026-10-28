#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env
set +a
python3 scripts/demo.py
