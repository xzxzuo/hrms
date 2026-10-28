#!/usr/bin/env sh
set -eu
mvn -B verify "$@"
