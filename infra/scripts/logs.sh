#!/bin/sh
# Follow logs, optionally for given services: logs.sh backend freeradius
set -eu
cd "$(dirname "$0")/.."
docker compose logs -f --tail=200 "$@"
