#!/bin/sh
# Build and start the local stack (PostgreSQL, RabbitMQ, Redis, backend, FreeRADIUS, Caddy).
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "infra/.env missing: copy infra/.env.example and set passwords" >&2; exit 1; }
docker compose up -d --build --wait "$@"
docker compose ps
