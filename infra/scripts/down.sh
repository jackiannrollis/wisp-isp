#!/bin/sh
# Stop the local stack. Pass -v to also delete the database and broker volumes.
set -eu
cd "$(dirname "$0")/.."
docker compose down "$@"
