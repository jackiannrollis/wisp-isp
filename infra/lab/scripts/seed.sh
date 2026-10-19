#!/bin/sh
# Load the network-lab fixtures (infra/lab/seed.sql) into the running stack and restart FreeRADIUS,
# which reads its NAS list only at startup. Safe to re-run: it resets the lab lines' dates.
set -eu
cd "$(dirname "$0")/../.."

[ -f .env ] || { echo "infra/.env missing: copy infra/.env.example and set passwords" >&2; exit 1; }
set -a
. ./.env
set +a

# Secrets travel as environment variables (docker compose exec -e NAME), never as arguments.
export LAB_SECRET="${RADIUS_LAB_SECRET:?RADIUS_LAB_SECRET is not set in infra/.env}"
export CHR_TUNNEL_IP="${LAB_CHR_TUNNEL_IP:-10.200.0.11}"
export PGPASSWORD="${ISP_DB_OWNER_PASSWORD:?ISP_DB_OWNER_PASSWORD is not set in infra/.env}"

docker compose exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres \
    psql -h localhost -U isp_owner -d isp -q < lab/seed.sql
docker compose restart freeradius
