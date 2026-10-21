#!/bin/sh
# Start what the end-to-end suite needs (ADR 0012): PostgreSQL, RabbitMQ, Redis, the fake Daraja and
# the backend pointed at it. With --seed it also loads infra/lab/seed.sql (fresh databases, as in CI).
# infra/scripts/up.sh puts the backend back on the real Daraja sandbox afterwards.
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "infra/.env missing: copy infra/.env.example and set passwords" >&2; exit 1; }
compose() { docker compose -f docker-compose.yml -f e2e/compose.e2e.yml "$@"; }
compose up -d --build --wait postgres rabbitmq redis fake-daraja backend
if [ "${1:-}" = "--seed" ]; then
    set -a
    . ./.env
    set +a
    # Secrets travel as environment variables (docker compose exec -e NAME), never as arguments.
    export LAB_SECRET="${RADIUS_LAB_SECRET:?RADIUS_LAB_SECRET is not set in infra/.env}"
    export CHR_TUNNEL_IP="${LAB_CHR_TUNNEL_IP:-10.200.0.11}"
    export PGPASSWORD="${ISP_DB_OWNER_PASSWORD:?ISP_DB_OWNER_PASSWORD is not set in infra/.env}"
    compose exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres \
        psql -h localhost -U isp_owner -d isp -q -v ON_ERROR_STOP=1 < lab/seed.sql
fi
compose ps
