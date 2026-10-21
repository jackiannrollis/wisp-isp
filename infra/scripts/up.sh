#!/bin/sh
# Build and start the local stack (PostgreSQL, RabbitMQ, Redis, backend, FreeRADIUS, Caddy). It also
# removes the e2e stack's fake Daraja and points the backend at the real Daraja sandbox again (ADR 0012).
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "infra/.env missing: copy infra/.env.example and set passwords" >&2; exit 1; }
docker compose up -d --build --wait --remove-orphans "$@"
docker compose ps
