#!/bin/sh
# Disposable browser-regression stack. --fresh resets only this project's fake fixture database.
set -eu
cd "$(dirname "$0")/.."
node e2e/create-env.mjs
compose() { docker compose -p corebalt-e2e --env-file e2e/.env -f e2e/compose.isolated.yml "$@"; }
seed=false
fresh=false
for arg in "$@"; do
  case "$arg" in --seed) seed=true ;; --fresh) fresh=true ;; *) echo "Unknown argument: $arg" >&2; exit 1 ;; esac
done
if "$fresh"; then compose down --volumes --remove-orphans; fi
compose up -d --build --wait
if "$seed"; then
  export LAB_SECRET=fake-e2e-radius-secret CHR_TUNNEL_IP=10.200.0.11 PGPASSWORD=fake-e2e-owner-password
  compose exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres \
    psql -h localhost -U isp_owner -d isp -q -v ON_ERROR_STOP=1 < lab/seed.sql
fi
compose ps
