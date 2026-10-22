#!/usr/bin/env bash
# Local test of the staging/production stack (infra/stack/compose.yml), run through stackctl exactly as
# a host runs it, except: local images, Caddy's internal CA instead of ACME, *.localhost names, named
# volumes, fake secrets, ports 8081/8443 and RADIUS on 127.0.0.1:11812. It deploys a release, loads
# the lab seed, checks the routing from outside, opens both apps in a browser, then exercises deploy
# again (no change), a release whose API fails its health gate (rolled back), and a rollback. Then the
# backups against a local S3 server (versitygw): a full backup, the restore drill, disaster recovery
# from the repository, and the alert rules' unit tests. Leaves the stack running for a look at
# https://admin.corebalt.localhost:8443; "test.sh down" removes it.
set -euo pipefail
cd "$(dirname "$0")"
# Git Bash rewrites /path arguments for native programs; docker needs them as they are, curl does not.
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }
compose() {
    docker compose -f compose.yml -f compose.override.yml --env-file .env --env-file images.env "$@"
}
# Every *.corebalt.localhost name goes to this machine, whatever the resolver thinks of .localhost.
curl() { command curl --connect-to ::127.0.0.1: "$@"; }
# The stack directory is this folder: .env, images.env* and compose.override.yml are test files (git-ignored).
cleanup_files() { rm -rf .env images.env images.env.previous images.env.failed images.env.new compose.override.yml stackctl backupctl pgbackrest.conf metrics; }

if [ "${1:-}" = down ]; then
    if [ -f .env ]; then
        # compose needs the image variables to parse the file, whatever a failed run left behind.
        [ -f images.env ] || printf 'BACKEND_IMAGE=x
FREERADIUS_IMAGE=x
WEB_IMAGE=x
POSTGRES_IMAGE=x
' > images.env
        [ -f compose.override.yml ] || cp test.override.yml compose.override.yml
        compose down -v --remove-orphans
        docker volume rm -f corebalt-stacktest-drill >/dev/null 2>&1 || true
    fi
    cleanup_files
    exit 0
fi

if [ -z "${STACKTEST_SKIP_BUILD:-}" ]; then
    echo "== images"
    docker build -q -t corebalt-stacktest/backend ../../backend >/dev/null
    docker build -q -t corebalt-stacktest/freeradius ../../radius >/dev/null
    docker build -q -t corebalt-stacktest/web -f ../web/Dockerfile ../.. >/dev/null
    docker build -q -t corebalt-stacktest/postgres ../postgres >/dev/null
fi

# The Cloudflare plugin checks the token's shape (40 of [A-Za-z0-9_-]) when it loads: a fake of that shape.
echo "== Caddyfile parses in dns mode with both providers (the certificate itself needs a real zone)"
for provider in cloudflare hetzner; do
    docker run --rm -e PLATFORM_DOMAIN=example.test -e TLS_MODE=dns -e ACME_EMAIL=ops@example.test \
        -e ACME_DNS_PROVIDER=$provider -e ACME_DNS_API_TOKEN=fake0000000000000000000000000000000000Tk corebalt-stacktest/web \
        caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1 && echo "   ok   $provider" \
        || { echo "   FAIL Caddyfile with dns $provider"; exit 1; }
done

if [ ! -f .env ]; then
    fake() { echo "stacktest-$(openssl rand -hex 12)"; }
    key=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null \
        | openssl pkcs8 -topk8 -nocrypt -outform DER | openssl base64 -A)
    cat > .env <<EOF
# Throwaway values for the local stack test only.
PLATFORM_DOMAIN=corebalt.localhost
TLS_MODE=internal
ACME_EMAIL=ops@corebalt.localhost
HTTP_PORT=8081
HTTPS_PORT=8443
WG_HUB_IP=127.0.0.1
RADIUS_AUTH_PORT=11812
RADIUS_ACCT_PORT=11813
POSTGRES_SUPERUSER_PASSWORD=$(fake)
ISP_DB_OWNER_PASSWORD=$(fake)
ISP_DB_APP_PASSWORD=$(fake)
ISP_DB_WORKER_PASSWORD=$(fake)
RADIUS_DB_PASSWORD=$(fake)
RABBITMQ_PASSWORD=$(fake)
REDIS_PASSWORD=$(fake)
ISP_MASTER_KEY=$(openssl rand -base64 32)
ISP_JWT_KID=kstacktest
ISP_JWT_PRIVATE_KEY=$key
SMS_ALLOWLIST=+254700000001
# Backups: the drill's scratch store is a volume here (a directory on the data disk on a host), on the
# compose network where the local S3 server is; the metrics go to ./metrics.
DRILL_STORE=corebalt-stacktest-drill
DRILL_NETWORK=corebalt_default
METRICS_DIR=./metrics
EOF
fi
# The backup repository for the test: versitygw from compose.override.yml, self-signed, fake credentials
# (a host gets this file from Ansible, roles/stack/templates/pgbackrest.conf.j2). A fresh repository
# path per run: the database volume may be a new cluster since the last run, which an existing stanza
# would refuse ("test.sh down" removes the volume with the rest).
cat > pgbackrest.conf <<EOF
[global]
repo1-type=s3
repo1-s3-endpoint=s3
repo1-storage-port=7070
repo1-s3-uri-style=path
repo1-storage-verify-tls=n
repo1-s3-bucket=corebalt-stacktest
repo1-s3-region=us-east-1
repo1-s3-key=stacktest-access-key
repo1-s3-key-secret=stacktest-secret-key-not-a-secret
repo1-path=/corebalt-stacktest-$(date +%s)
repo1-cipher-type=aes-256-cbc
repo1-cipher-pass=stacktest-cipher-passphrase-not-a-secret
repo1-retention-full=30
repo1-bundle=y
repo1-block=y
compress-type=zst
process-max=2
start-fast=y
log-level-console=info
log-level-file=off

[main]
pg1-path=/var/lib/postgresql/data
pg1-socket-path=/var/run/postgresql
EOF
cp test.override.yml compose.override.yml
cp stackctl.sh stackctl && chmod +x stackctl
cp backupctl.sh backupctl && chmod +x backupctl
# Every run starts with nothing deployed; the containers of an earlier run are reused or recreated.
rm -f images.env images.env.previous images.env.failed images.env.new
release() { # <name> [backend image]: an images.env for a release
    printf 'BACKEND_IMAGE=%s\nFREERADIUS_IMAGE=corebalt-stacktest/freeradius\nWEB_IMAGE=corebalt-stacktest/web\nPOSTGRES_IMAGE=corebalt-stacktest/postgres\nRELEASE=%s\nDEPLOYED_AT=%s\n' \
        "${2:-corebalt-stacktest/backend}" "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > images.env.new
}

fail=0
echo "== first deploy (stackctl deploy: pull, the foundation and the backup stanza, then every service behind its health gate)"
release stacktest-1
if ! out=$(./stackctl deploy images.env.new 2>&1); then echo "$out"; echo "FAIL: first deploy"; exit 1; fi
echo "$out" | grep -E '^   ' || true
echo "$out" | grep -q 'backups ok' && echo "   ok   backup stanza created and WAL archiving checked" || { echo "   FAIL backups on the first deploy"; fail=1; }

echo "== lab seed"
set -a; . ./.env; set +a
LAB_SECRET=stacktest-lab-secret CHR_TUNNEL_IP=10.200.0.11 PGPASSWORD="$ISP_DB_OWNER_PASSWORD" \
    compose exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres \
    psql -h localhost -U isp_owner -d isp -q -v ON_ERROR_STOP=1 < ../lab/seed.sql >/dev/null
# A wallet and three ledger entries for CB000001 (the seed has none), so the restore drill's ledger
# checks and the disaster recovery below run on real rows (B3: each balance follows from the previous
# entry; B4: the cached balance is the last entry's). Replaced on every run, like the seed.
compose exec -T -u postgres postgres psql -X -q -v ON_ERROR_STOP=1 -d isp <<'SQL'
delete from billing_ledger_entry where reference_type = 'STACKTEST';
delete from billing_wallet w using customers_customer c
    where c.tenant_id = w.tenant_id and c.id = w.customer_id and c.number = 'CB000001';
insert into billing_wallet (id, tenant_id, customer_id, balance_minor)
    select gen_random_uuid(), tenant_id, id, 15000 from customers_customer where number = 'CB000001';
insert into billing_ledger_entry (id, tenant_id, wallet_id, type, amount_minor, balance_after_minor, currency,
                                  reference_type, reference_id, description, reason, created_at)
    select gen_random_uuid(), w.tenant_id, w.id, t.type, t.amount, t.balance, 'KES', 'STACKTEST', gen_random_uuid(),
           t.descr, t.reason, now() - t.age
    from billing_wallet w
    join customers_customer c on c.tenant_id = w.tenant_id and c.id = w.customer_id and c.number = 'CB000001'
    cross join (values ('PAYMENT', 50000, 50000, 'M-Pesa payment', null, interval '3 hours'),
                       ('RENEWAL_CHARGE', -30000, 20000, 'Renewal', null, interval '2 hours'),
                       ('ADJUSTMENT', -5000, 15000, 'Correction', 'stack test', interval '1 hour'))
         as t(type, amount, balance, descr, reason, age);
SQL

echo "== routing, from outside"
check() { # name expected-status url [curl args]
    local name=$1 want=$2 url=$3; shift 3
    local got
    got=$(curl -sk -o /dev/null -w '%{http_code} %{redirect_url}' "$@" "$url" || true)
    if echo "${got%% *}" | grep -Eqx "$want"; then echo "   ok   $name ($got)"; else echo "   FAIL $name: want $want, got $got"; fail=1; fi
}
B=https://corebalt.localhost:8443
check "admin app"                 200 https://admin.corebalt.localhost:8443/
check "admin deep link (SPA)"     200 https://admin.corebalt.localhost:8443/customers/123
check "API through the admin host" 401 https://admin.corebalt.localhost:8443/api/v1/dashboard
check "tenant portal (wildcard)"  200 https://cb.corebalt.localhost:8443/status
check "pay page"                  200 https://pay.corebalt.localhost:8443/
# A fresh number each run; 429 is the sign-in code limit (ADR 0010) after many runs, the API reached all the same.
check "portal sign-in API"        '202|429' https://pay.corebalt.localhost:8443/api/v1/portal-auth/code \
    -H 'Content-Type: application/json' -H 'Origin: https://pay.corebalt.localhost:8443' \
    -d "{\"tenant\":\"CB\",\"phone\":\"07$(printf '%08d' $((RANDOM * 3 + 10000000)))\"}"
check "metrics not public"        404 https://admin.corebalt.localhost:8443/actuator/prometheus
check "health not public"         404 "$B/actuator/health"
check "main domain to admin"      302 "$B/"
check "M-Pesa callback refused (not Safaricom)" 403 "$B/callbacks/mpesa/not-a-token/c2b/confirmation" \
    -H 'Content-Type: application/json' -d '{}'
check "redirected HTTP to the pay page" 302 http://example.com:8081/ --connect-to example.com:8081:127.0.0.1:8081
check "security headers"          200 https://admin.corebalt.localhost:8443/ -o /dev/null
headers=$(curl -sk -D - -o /dev/null https://admin.corebalt.localhost:8443/)
for h in strict-transport-security content-security-policy x-frame-options x-content-type-options; do
    echo "$headers" | grep -qi "^$h:" && echo "   ok   header $h" || { echo "   FAIL header $h missing"; fail=1; }
done
echo "$headers" | grep -qi '^server:' && { echo "   FAIL Server header present"; fail=1; } || echo "   ok   no Server header"
echo "$headers" | grep -qi '^cache-control: no-cache' && echo "   ok   index.html no-cache" || { echo "   FAIL index.html cache header"; fail=1; }
bundle=$(curl -sk https://admin.corebalt.localhost:8443/ | grep -o 'main-[A-Za-z0-9_-]*\.js' | head -1)
curl -sk -D - -o /dev/null "https://admin.corebalt.localhost:8443/$bundle" | grep -qi 'cache-control: public, max-age=31536000, immutable' \
    && echo "   ok   hashed bundle immutable ($bundle)" || { echo "   FAIL bundle cache header ($bundle)"; fail=1; }

echo "== both apps in a browser through the front (sign-in, a page each; any console error fails, CSP included)"
if [ -d ../../frontend/node_modules/@playwright ]; then
    (cd ../../frontend && node e2e/stack-smoke.mjs https://corebalt.localhost:8443) || fail=1
else
    echo "   skipped: frontend/node_modules missing (npm ci in frontend/ first)"
fi

echo "== deploy again: the same release changes nothing"
release stacktest-1
# Outputs are captured before grep: with pipefail, grep -q closing the pipe early would fail the pipeline.
out=$(./stackctl deploy images.env.new); echo "$out" | grep -q 'already deployed' && echo "   ok   already deployed" || { echo "   FAIL"; echo "$out"; fail=1; }

echo "== a release whose API never gets healthy is rolled back (the other instance kept serving)"
release stacktest-2 alpine:3.22
if ./stackctl deploy images.env.new > deploy.log 2>&1; then echo "   FAIL deploy of a broken release succeeded"; fail=1; fi
grep -q 'back on stacktest-1' deploy.log && echo "   ok   rolled back to stacktest-1" || { echo "   FAIL no rollback message"; cat deploy.log; fail=1; }
[ "$(sed -n 's/^RELEASE=//p' images.env)" = stacktest-1 ] && echo "   ok   images.env is the previous release again" || { echo "   FAIL images.env"; fail=1; }
[ "$(sed -n 's/^RELEASE=//p' images.env.failed)" = stacktest-2 ] && echo "   ok   the failed release is kept as images.env.failed" || { echo "   FAIL images.env.failed"; fail=1; }
compose ps --format '{{.Service}} {{.Status}}' | grep -q 'api-1 Up.*(healthy)' && echo "   ok   api-1 healthy on the old image" || { echo "   FAIL api-1 not healthy"; fail=1; }
check "API still answering"       401 https://admin.corebalt.localhost:8443/api/v1/dashboard
rm -f deploy.log

echo "== a good release (the same backend under a new reference, so it is a real rollout), then stackctl rollback"
docker tag corebalt-stacktest/backend corebalt-stacktest/backend:release-3
release stacktest-3 corebalt-stacktest/backend:release-3
out=$(./stackctl deploy images.env.new); echo "$out" | grep -q 'stacktest-3 deployed' && echo "   ok   stacktest-3 deployed" || { echo "   FAIL deploy"; echo "$out"; fail=1; }
out=$(./stackctl rollback); echo "$out" | grep -q 'back on release stacktest-1' && echo "   ok   back on stacktest-1" || { echo "   FAIL rollback"; echo "$out"; fail=1; }
out=$(./stackctl status); echo "$out" | grep -q 'release stacktest-1 (previous: stacktest-3)' && echo "   ok   status: stacktest-1, previous stacktest-3" || { echo "   FAIL status"; echo "$out"; fail=1; }

echo "== backup to the local S3 server: a full backup with annotations, then the metrics"
if out=$(./backupctl backup 2>&1); then echo "$out" | grep -E '^   ' || true; echo "   ok   backup"; else echo "$out"; echo "   FAIL backup"; fail=1; fi
grep -q '^corebalt_backup_last_run_success 1$' metrics/corebalt_backup.prom 2>/dev/null && echo "   ok   metrics: last run succeeded" \
    || { echo "   FAIL backup metrics"; cat metrics/corebalt_backup.prom 2>/dev/null; fail=1; }
good=$(sed -n 's/^corebalt_backup_last_good_timestamp_seconds //p' metrics/corebalt_backup.prom 2>/dev/null)
[ "${good:-0}" -gt 0 ] 2>/dev/null && echo "   ok   metrics: last good backup at $good" || { echo "   FAIL last good backup timestamp ($good)"; fail=1; }

echo "== restore drill: the newest backup into a scratch instance; schema, row counts and the ledger checked"
if out=$(./backupctl drill 2>&1); then echo "$out" | grep -E '^   ' || true; echo "   ok   drill"; else echo "$out"; echo "   FAIL drill"; fail=1; fi
grep -q '^corebalt_backup_drill_success 1$' metrics/corebalt_backup_drill.prom 2>/dev/null && echo "   ok   metrics: drill succeeded" \
    || { echo "   FAIL drill metrics"; cat metrics/corebalt_backup_drill.prom 2>/dev/null; fail=1; }
grep -q 'corebalt_backup_drill_rows{table="billing_ledger_entry"} [1-9]' metrics/corebalt_backup_drill.prom 2>/dev/null \
    && echo "   ok   metrics: ledger rows restored" || { echo "   FAIL ledger rows metric"; fail=1; }
docker ps -a --format '{{.Names}}' | grep -q '^corebalt-drill$' && { echo "   FAIL scratch instance left behind"; fail=1; } || echo "   ok   scratch instance removed"

echo "== disaster recovery: the stack stopped, the database volume gone, restored from the repository, back up"
before=$(compose exec -T -u postgres postgres psql -X -Atq -d isp -c "select count(*) from billing_ledger_entry")
./stackctl stop >/dev/null 2>&1
compose rm -sf postgres >/dev/null 2>&1
docker volume rm corebalt_pg >/dev/null
compose up -d --wait s3 >/dev/null 2>&1   # the repository: external to a host, a service here
if out=$(./backupctl restore 2>&1); then echo "$out" | tail -1 | sed 's/^/   /'; else echo "$out"; echo "   FAIL restore"; fail=1; fi
./stackctl up >/dev/null 2>&1 && echo "   ok   stack up again (recovery replayed the archive and promoted)" || { echo "   FAIL stack up after the restore"; fail=1; }
after=$(compose exec -T -u postgres postgres psql -X -Atq -d isp -c "select count(*) from billing_ledger_entry" 2>/dev/null || echo none)
[ -n "$before" ] && [ "$after" = "$before" ] && echo "   ok   ledger entries back: $after" || { echo "   FAIL ledger entries: $before before, $after after"; fail=1; }
compose exec -T -u postgres postgres psql -X -Atq -d isp -c "select number from customers_customer where number = 'CB000001'" 2>/dev/null | grep -q CB000001 \
    && echo "   ok   customer CB000001 back" || { echo "   FAIL customer CB000001 missing"; fail=1; }
check "API after the restore"     401 https://admin.corebalt.localhost:8443/api/v1/dashboard

echo "== backup alert rules (promtool check and unit tests; the monitoring stack evaluates them)"
mon=$(cd ../monitoring && (pwd -W 2>/dev/null || pwd))
docker run --rm -v "$mon:/m:ro" --entrypoint promtool prom/prometheus:v3.15.1 check rules /m/rules/backups.yml >/dev/null 2>&1 \
    && echo "   ok   rules parse" || { echo "   FAIL rules do not parse"; docker run --rm -v "$mon:/m:ro" --entrypoint promtool prom/prometheus:v3.15.1 check rules /m/rules/backups.yml; fail=1; }
if out=$(docker run --rm -v "$mon:/m:ro" -w /m/tests --entrypoint promtool prom/prometheus:v3.15.1 test rules backups.yml 2>&1); then echo "   ok   rule unit tests"
else echo "$out" | tail -40; echo "   FAIL rule unit tests"; fail=1; fi

echo "== services"
./stackctl status
./backupctl status | sed -n '1,12p'
[ "$fail" = 0 ] && echo "STACK TEST PASSED" || { echo "STACK TEST FAILED"; exit 1; }
