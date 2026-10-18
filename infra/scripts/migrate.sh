#!/bin/sh
# Apply Flyway migrations without starting the API (the API also migrates on startup).
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --wait postgres
docker compose --profile tools run --rm flyway migrate
