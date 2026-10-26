#!/usr/bin/env bash
# Local test of the staging/production stack (infra/stack/compose.yml), run through stackctl exactly as
# a host runs it, except: local images, Caddy's internal CA instead of ACME, *.localhost names, named
# volumes, fake secrets, ports 8081/8443 and RADIUS on 127.0.0.1:11812. It deploys a release, loads
# the lab seed, checks the routing from outside, opens both apps in a browser, then exercises deploy
# again (no change), a release whose API fails its health gate (rolled back), and a rollback. Then the
# backups against a local S3 server (versitygw): a full backup, the restore drill, disaster recovery
# from the repository. The monitoring project (infra/monitoring) runs beside the stack as on a host:
# its configurations are validated and the rules unit-tested, every scrape target must come up, the
# Watchdog must reach Alertmanager, the public names are probed, the API's logs land in Loki with their
# tenant, a RADIUS login and a reject go through FreeRADIUS into the status probe and the auth log, and
# Grafana serves every dashboard. Leaves everything running for a look at
# https://admin.corebalt.localhost:8443 and http://127.0.0.1:3000 (Grafana); "test.sh down" removes it.
set -euo pipefail
cd "$(dirname "$0")"
# Git Bash rewrites /path arguments for native programs; docker needs them as they are, curl does not.
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }
compose() {
    docker compose -f compose.yml -f compose.override.yml --env-file .env --env-file images.env "$@"
}
# Every *.corebalt.localhost name goes to this machine, whatever the resolver thinks of .localhost.
curl() { command curl --connect-to ::127.0.0.1: "$@"; }
# The stack directory is this folder: .env, images.env* and compose.override.yml are test files (git-ignored);
# the monitoring project's test files live in ../monitoring the same way.
MON=../monitoring
cleanup_files() {
    rm -rf .env images.env images.env.previous images.env.failed images.env.new compose.override.yml stackctl backupctl pgbackrest.conf metrics
    rm -rf "$MON/.env" "$MON/compose.override.yml" "$MON/alertmanager.yml" "$MON/secrets" "$MON/targets.d" "$MON/monitorctl" "$MON/probes"
}
mon() { (cd "$MON" && docker compose -f compose.yml -f compose.override.yml --env-file .env "$@"); }
envval() { sed -n "s/^$1=//p" .env 2>/dev/null | head -1; }
fake() { echo "stacktest-$(openssl rand -hex 12)"; }

if [ "${1:-}" = down ]; then
    # The monitoring project first: it uses the stack's network, which the stack's down removes.
    if [ -f "$MON/.env" ] && [ -f "$MON/compose.override.yml" ]; then mon down -v --remove-orphans; fi
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
# Monitoring (infra/monitoring): the status probe's secret and the pg_monitor role's password. Added to
# an .env from before they existed.
grep -q '^RADIUS_STATUS_SECRET=' .env || printf 'RADIUS_STATUS_SECRET=%s\nISP_DB_MONITOR_PASSWORD=%s\n' "$(fake)" "$(fake)" >> .env
# The first platform admin (ADR 0010), for the onboarding recipe below; its password is reset by the
# break-glass command at every run, so the value here matters only for the first start.
grep -q '^ISP_BOOTSTRAP_ADMIN_EMAIL=' .env || printf 'ISP_BOOTSTRAP_ADMIN_EMAIL=platform-admin@corebalt.localhost\nISP_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$(fake)" >> .env
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
# The owner password is read, not sourced: exported stack settings (METRICS_DIR=./metrics) would otherwise
# override the monitoring project's own .env when compose runs below.
LAB_SECRET=stacktest-lab-secret CHR_TUNNEL_IP=10.200.0.11 PGPASSWORD="$(envval ISP_DB_OWNER_PASSWORD)" \
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

echo "== monitoring (infra/monitoring): the host-specific files, every configuration validated, the rule unit tests, then up"
# What Ansible writes on a host (roles/monitoring): .env, Alertmanager without a notification channel
# (alerts show in its API, which the checks read), the probe targets with the internal CA, named volumes.
mkdir -p metrics "$MON/secrets" "$MON/targets.d"
rm -f metrics/*.prom    # an earlier run's files would be read before this run writes its own
touch metrics/.keep "$MON/secrets/.keep"
# Grafana stores the admin password at its first start, so a later run keeps the one its volume knows.
gp=$(grep -s '^GRAFANA_ADMIN_PASSWORD=' "$MON/.env" | head -1 | cut -d= -f2-) || true
[ -n "$gp" ] || gp=$(fake)
cat > "$MON/.env" <<EOF
# Throwaway values for the local stack test only (a host gets this file from Ansible, roles/monitoring).
MONITORING_ENV=local
MONITORING_HOST=stacktest
GRAFANA_ADMIN_PASSWORD=$gp
ISP_DB_MONITOR_PASSWORD=$(envval ISP_DB_MONITOR_PASSWORD)
REDIS_PASSWORD=$(envval REDIS_PASSWORD)
METRICS_DIR=$(pwd -W 2>/dev/null || pwd)/metrics
EOF
cat > "$MON/alertmanager.yml" <<'EOF'
route:
  receiver: none
  group_by: [alertname, host]
  routes:
    - matchers: ['alertname = Watchdog']
      receiver: none
      repeat_interval: 5m
receivers:
  - name: none
EOF
cat > "$MON/targets.d/web.yml" <<'EOF'
- labels: { module: https_internal }
  targets: [https://admin.corebalt.localhost/, https://pay.corebalt.localhost/, https://corebalt.localhost/]
EOF
cp "$MON/test.override.yml" "$MON/compose.override.yml"
cp "$MON/monitorctl.sh" "$MON/monitorctl" && chmod +x "$MON/monitorctl"
cp "$MON/probes.sh" "$MON/probes" && chmod +x "$MON/probes"
if out=$("$MON/monitorctl" check 2>&1); then echo "$out"; else echo "$out"; echo "   FAIL monitoring configuration checks"; fail=1; fi
if out=$("$MON/monitorctl" up 2>&1); then echo "   ok   monitoring up"; else echo "$out" | tail -30; echo "   FAIL monitoring up"; fail=1; fi

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
smoke_ran=0
if [ -d ../../frontend/node_modules/@playwright ]; then
    (cd ../../frontend && node e2e/stack-smoke.mjs https://corebalt.localhost:8443) && smoke_ran=1 || fail=1
else
    echo "   skipped: frontend/node_modules missing (npm ci in frontend/ first)"
fi

echo "== monitoring: scrape targets, rules, the Watchdog in Alertmanager, probes, logs in Loki, Grafana"
PY=$(command -v python3 || command -v python)
urlenc() { printf '%s' "$1" | "$PY" -I -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.stdin.read()))'; }
values() { "$PY" -I -c 'import json, sys; print(" ".join(x["value"][1] for x in json.load(sys.stdin)["data"]["result"]))'; }
prom() { curl -sf --max-time 10 "http://127.0.0.1:9090/api/v1/$1"; }
promq() { prom "query?query=$(urlenc "$1")" | values; }
# Loki is not published; ask it from inside the project (the Prometheus image has wget).
loki_get() { mon exec -T prometheus wget -qO- -T 10 "http://loki:3100/loki/api/v1/$1" 2>/dev/null; }
lokiq() { loki_get "query?query=$(urlenc "$1")" | values; }
gf() { curl -sf --max-time 10 -u "admin:$gp" "http://127.0.0.1:3000/api/$1"; }
# The checks, as functions (until_ok runs them in this shell): <expr> equals, is non-empty, counts above zero.
q_eq() { [ "$(promq "$1")" = "$2" ]; }
q_nonempty() { [ -n "$(promq "$1")" ]; }
loki_gt0() { local v; v=$(lokiq "$1"); [ -n "$v" ] && [ "${v%.*}" -gt 0 ]; }
loki_nonempty() { [ -n "$(lokiq "$1")" ]; }
loki_zero() { local v; v=$(lokiq "$1"); [ -z "$v" ] || [ "${v%.*}" -eq 0 ]; }
# <name> <seconds> <check...>: retried until the check succeeds or the time is up; a failure is recorded
# in $fail (never returned, so set -e carries on).
until_ok() {
    local name=$1 secs=$2; shift 2
    local end=$((SECONDS + secs))
    while :; do
        if "$@" >/dev/null 2>&1; then echo "   ok   $name"; return 0; fi
        [ $SECONDS -lt $end ] || { echo "   FAIL $name (after ${secs}s)"; fail=1; return 0; }
        sleep 3
    done
}
targets_all_up() { # every target scraped, at least 18 of them (prometheus.yml plus the three probes)
    prom targets | "$PY" -I -c '
import json, sys
t = json.load(sys.stdin)["data"]["activeTargets"]
down = [x for x in t if x["health"] != "up"]
sys.exit(0 if len(t) >= 18 and not down else 1)'
}
targets_down() {
    prom targets | "$PY" -I -c '
import json, sys
for x in json.load(sys.stdin)["data"]["activeTargets"]:
    if x["health"] != "up": print("        %s %s: %s" % (x["labels"]["job"], x["labels"]["instance"], (x.get("lastError") or x["health"])[:160]))'
}
watchdog_in_alertmanager() { curl -sf --max-time 5 'http://127.0.0.1:9093/api/v2/alerts?filter=alertname%3D%22Watchdog%22' | grep -q '"alertname":"Watchdog"'; }
cert_alert_pending() { prom alerts | grep -q CorebaltCertificateExpiring; }
api_scraped() { q_eq 'count(up{job="api"} == 1)' 2 && q_nonempty 'count(http_server_requests_seconds_count{job="api"}) > 0'; }
loki_rules_loaded() { loki_get rules | grep -q CorebaltRadiusTenantRejectRateHigh; }
grafana_healthy() { curl -sf --max-time 5 http://127.0.0.1:3000/api/health | grep -q '"database": *"ok"'; }

until_ok "every scrape target up" 120 targets_all_up
targets_all_up >/dev/null 2>&1 || targets_down || true
groups=$(prom rules | "$PY" -I -c 'import json, sys; print(len(json.load(sys.stdin)["data"]["groups"]))') || groups=0
[ "${groups:-0}" -ge 10 ] && echo "   ok   $groups rule groups loaded" || { echo "   FAIL rule groups loaded: $groups"; fail=1; }
until_ok "text-file metrics readable (node_textfile_scrape_error 0)" 30 q_eq node_textfile_scrape_error 0
until_ok "Watchdog received by Alertmanager" 90 watchdog_in_alertmanager
until_ok "admin app probed through the front (probe_success 1)" 60 q_eq 'probe_success{instance="https://admin.corebalt.localhost/"}' 1
until_ok "certificate expiry read from the probe" 30 q_nonempty 'probe_ssl_earliest_cert_expiry{instance="https://pay.corebalt.localhost/"} > time()'
until_ok "the internal CA's 12-hour certificate raises CorebaltCertificateExpiring (pending or firing)" 60 cert_alert_pending
until_ok "both API instances scraped, with request metrics" 30 api_scraped
until_ok "PostgreSQL exporter connected as isp_monitor (pg_up 1)" 30 q_eq pg_up 1
until_ok "Redis exporter (redis_up 1)" 30 q_eq redis_up 1
until_ok "RabbitMQ per-queue metrics" 30 q_nonempty 'sum(rabbitmq_detailed_queue_messages)'
until_ok "web front metrics (caddy)" 30 q_nonempty 'sum(caddy_http_requests_total)'
until_ok "API logs in Loki" 90 loki_gt0 'sum(count_over_time({service="api-1"}[15m]))'
if [ "$smoke_ran" = 1 ]; then
    until_ok "API lines carry tenant_id as structured metadata" 60 loki_gt0 'sum(count_over_time({service=~"api-1|api-2"} | tenant_id != "" [15m]))'
fi
until_ok "Loki ruler loaded the LogQL alerts" 60 loki_rules_loaded
until_ok "Grafana healthy" 60 grafana_healthy
ds=$(gf datasources | "$PY" -I -c 'import json, sys; print(len(json.load(sys.stdin)))' 2>/dev/null) || ds=0
[ "${ds:-0}" = 3 ] && echo "   ok   3 data sources provisioned" || { echo "   FAIL data sources: $ds"; fail=1; }
for d in prometheus loki; do
    gf "datasources/uid/$d/health" | grep -q '"status": *"OK"' && echo "   ok   data source $d healthy" || { echo "   FAIL data source $d health: $(gf "datasources/uid/$d/health")"; fail=1; }
done
# The Alertmanager data source has no health check in Grafana; its proxy reaching Alertmanager is the test.
gf "datasources/proxy/uid/alertmanager/api/v2/status" | grep -q '"cluster"' && echo "   ok   data source alertmanager reachable through Grafana" \
    || { echo "   FAIL data source alertmanager through Grafana"; fail=1; }
dash=$(gf 'search?type=dash-db' | "$PY" -I -c 'import json, sys; print(len(json.load(sys.stdin)))' 2>/dev/null) || dash=0
[ "${dash:-0}" = 6 ] && echo "   ok   6 dashboards provisioned" || { echo "   FAIL dashboards: $dash"; fail=1; }
for d in platform api data network backups logs; do
    gf "dashboards/uid/corebalt-$d" | grep -q '"uid": *"corebalt-'"$d"'"' && echo "   ok   dashboard corebalt-$d" || { echo "   FAIL dashboard corebalt-$d"; fail=1; }
done

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

echo "== RADIUS: stackctl reload-radius (what a router registration needs), a lab login and a suspended one through FreeRADIUS, the status probe, the auth log"
if out=$(./stackctl reload-radius 2>&1) && echo "$out" | grep -q 'FreeRADIUS restarted'; then echo "   ok   $out"; else echo "$out"; echo "   FAIL stackctl reload-radius"; fail=1; fi
rad() { compose exec -T -e RADIUS_LAB_SECRET=stacktest-lab-secret freeradius corebalt-radtest "$@" 2>&1; }
if out=$(rad CB000001 lab-password) && echo "$out" | grep -q 'Access-Accept'; then echo "   ok   CB000001 accepted"
else echo "$out" | tail -5; echo "   FAIL CB000001 login"; fail=1; fi
if out=$(rad CB000006 lab-password); then echo "$out" | tail -3; echo "   FAIL CB000006 (suspended) was accepted"; fail=1
elif echo "$out" | grep -q 'Access-Reject'; then echo "   ok   CB000006 rejected (suspended, N3)"
else echo "$out" | tail -5; echo "   FAIL CB000006: no reply"; fail=1; fi
if out=$("$MON/probes" radius 2>&1); then echo "   ok   status probe"; else echo "$out"; echo "   FAIL status probe"; fail=1; fi
grep -q '^corebalt_radius_up 1$' metrics/corebalt_radius.prom 2>/dev/null && grep -Eq '^corebalt_radius_access_rejects_total [1-9]' metrics/corebalt_radius.prom \
    && echo "   ok   counters written: $(grep -E '^corebalt_radius_access_(requests|accepts|rejects)_total' metrics/corebalt_radius.prom | tr '\n' ' ')" \
    || { echo "   FAIL RADIUS metrics"; cat metrics/corebalt_radius.prom 2>/dev/null; fail=1; }
until_ok "RADIUS counters scraped (corebalt_radius_up 1)" 60 q_eq corebalt_radius_up 1
until_ok "text-file metrics still readable" 30 q_eq node_textfile_scrape_error 0
until_ok "the accepted login in Loki (radius_outcome OK)" 90 loki_gt0 'sum(count_over_time({service="freeradius"} | radius_outcome="OK" [15m]))'
until_ok "the rejected login in Loki (radius_outcome incorrect)" 60 loki_gt0 'sum(count_over_time({service="freeradius"} | radius_outcome="incorrect" [15m]))'
until_ok "the per-tenant query of the Loki rule finds tenant CB" 60 loki_nonempty 'sum by (tenant) (count_over_time({service="freeradius"} | radius_outcome=~"OK|incorrect" | regexp `\[(?P<tenant>[A-Z]+)[0-9]{6}` | tenant != "" [15m]))'
# The worker and api-2 run no scheduled job (isp.scheduling.enabled=false): before Modulith's moments
# auto-configuration was turned off, every job ran there too and failed as isp_worker every 10 seconds.
# Looked at over the current worker container's lifetime (it was recreated by the recovery above).
worker_age_min=$(( ($(date +%s) - $(date -d "$(docker inspect -f '{{.State.StartedAt}}' corebalt-tunnel-worker-1)" +%s)) / 60 + 1 ))
until_ok "the tunnel worker runs no API job (no 'failed for tenant' lines in its ${worker_age_min} minutes)" 10 \
    loki_zero "sum(count_over_time({service=\"tunnel-worker\"} |= \"failed for tenant\" [${worker_age_min}m]))"

echo "== onboarding (infra/ops/runbooks/pilot-onboarding.md, by its recipes): break-glass reset of the platform admin, its first sign-in, a tenant with its Owner, the Owner's first sign-in, a router, a package, a CSV import"
API=https://admin.corebalt.localhost:8443
jf() { "$PY" -I -c "import json, sys; d = json.load(sys.stdin); print($1)" 2>/dev/null; }
totp() { "$PY" -I -c 'import base64, hashlib, hmac, struct, sys, time
s = sys.argv[1]; key = base64.b32decode(s + "=" * (-len(s) % 8))
h = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest(); o = h[-1] & 15
print("%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7fffffff) % 1000000))' "$1"; }
post() { # <path> <json body> [curl args]: prints the response body
    local path=$1 body=$2; shift 2
    curl -sk --max-time 30 -H 'Content-Type: application/json' "$@" -d "$body" "$API$path"
}
step() { # <name> <wanted status> <response>: the sign-in status of a response
    local st; st=$(echo "$3" | jf 'd["status"]')
    if [ "$st" = "$2" ]; then echo "   ok   $1 ($2)"; else echo "   FAIL $1: wanted $2, got ${st:-no status}: $(echo "$3" | head -c 300)"; fail=1; return 1; fi
}
admin_email=$(envval ISP_BOOTSTRAP_ADMIN_EMAIL)
# The break-glass reset (ADR 0010; infra/scripts/reset-platform-admin.sh runs the same on the local stack)
# prints a temporary password once and clears TOTP, so this section runs the same on every run. The
# password is read into a variable, never printed.
tmp=$(compose exec -T -e ISP_OPERATOR=stacktest api-1 java -jar /app/app.jar reset-platform-admin "$admin_email" 2>/dev/null | sed -n 's/^    \([^ ]\{8,\}\)$/\1/p' | tail -1)
[ -n "$tmp" ] && echo "   ok   platform admin reset by the break-glass command (temporary password printed once, not here)" || { echo "   FAIL the break-glass reset printed no temporary password"; fail=1; }
r=$(post /api/platform/auth/login "{\"email\":\"$admin_email\",\"password\":\"$tmp\"}")
if step "platform sign-in asks for a new password" CHANGE_PASSWORD "$r"; then
    r=$(post /api/platform/auth/password "{\"challenge\":\"$(echo "$r" | jf 'd["challenge"]')\",\"newPassword\":\"$(fake)\"}")
    if step "then TOTP enrolment (secret, otpauth URI and recovery codes in this answer only)" TOTP_ENROLL "$r"; then
        [ "$(echo "$r" | jf 'len(d["recoveryCodes"])')" -ge 1 ] && echo "   ok   recovery codes came with the enrolment" || { echo "   FAIL no recovery codes"; fail=1; }
        r=$(post /api/platform/auth/totp/enroll "{\"challenge\":\"$(echo "$r" | jf 'd["challenge"]')\",\"code\":\"$(totp "$(echo "$r" | jf 'd["totpSecret"]')")\"}")
        step "enrolled with a code from the secret: signed in" SIGNED_IN "$r" && ptoken=$(echo "$r" | jf 'd["accessToken"]')
    fi
fi
code=$(openssl rand -hex 2 | tr '0-9a-f' 'A-P'); sub=$(echo "$code" | tr 'A-P' 'a-p')   # a new tenant every run
r=$(post /api/platform/tenants "{\"code\":\"$code\",\"name\":\"Stack Test ISP $code\",\"subdomain\":\"$sub\",\"ownerEmail\":\"owner@$sub.corebalt.localhost\",\"ownerName\":\"Stack Owner\",\"ownerPhone\":\"+254700000001\"}" -H "Authorization: Bearer ${ptoken:-none}")
otp=$(echo "$r" | jf 'd["owner"]["temporaryPassword"]')
if [ "$(echo "$r" | jf 'd["tenant"]["status"]')" = TRIAL ] && [ -n "$otp" ]; then echo "   ok   tenant $code created in TRIAL; its Owner's temporary password is in this answer only"
else echo "   FAIL tenant creation: $(echo "$r" | head -c 300)"; fail=1; fi
r=$(post /api/v1/auth/login "{\"tenant\":\"$code\",\"email\":\"owner@$sub.corebalt.localhost\",\"password\":\"$otp\"}")
if step "the Owner's first sign-in asks for a new password" CHANGE_PASSWORD "$r"; then
    r=$(post /api/v1/auth/password "{\"challenge\":\"$(echo "$r" | jf 'd["challenge"]')\",\"newPassword\":\"$(fake)\"}")
    if step "then TOTP enrolment (the Owner role requires 2FA, A2)" TOTP_ENROLL "$r"; then
        r=$(post /api/v1/auth/totp/enroll "{\"challenge\":\"$(echo "$r" | jf 'd["challenge"]')\",\"code\":\"$(totp "$(echo "$r" | jf 'd["totpSecret"]')")\"}")
        step "the Owner is signed in" SIGNED_IN "$r" && stoken=$(echo "$r" | jf 'd["accessToken"]')
    fi
fi
auth=(-H "Authorization: Bearer ${stoken:-none}")
tip="127.0.$((RANDOM % 250 + 1)).$((RANDOM % 250 + 2))"   # tunnel addresses are unique across tenants; a new one every run
r=$(post /api/v1/routers "{\"name\":\"stacktest-radtest\",\"tunnelIp\":\"$tip\"}" "${auth[@]}")
rid=$(echo "$r" | jf 'd["router"]["id"]')
[ -n "$rid" ] && [ -n "$(echo "$r" | jf 'd["radiusSecret"]')" ] && echo "   ok   router registered; its RADIUS secret is in this answer only (ADR 0009)" || { echo "   FAIL router registration: $(echo "$r" | head -c 300)"; fail=1; }
compose exec -T -u postgres postgres psql -X -Atq -d isp -c "select count(*) from radius_client_v3 where nasname = '$tip'" 2>/dev/null | grep -qx 1 \
    && echo "   ok   the router is a client in radius_client_v3 (what stackctl reload-radius makes FreeRADIUS load)" || { echo "   FAIL the router is missing from radius_client_v3"; fail=1; }
r=$(post /api/v1/packages '{"name":"Home 10","downloadKbps":10000,"uploadKbps":5000,"durationMinutes":43200,"price":{"amount":250000,"currency":"KES"}}' "${auth[@]}")
[ -n "$(echo "$r" | jf 'd["id"]')" ] && echo "   ok   package created" || { echo "   FAIL package: $(echo "$r" | head -c 300)"; fail=1; }
csv=$(printf 'name,phone,package\nJane Test,0700000002,Home 10\nJohn Test,0700000003,\n')
r=$(curl -sk --max-time 30 -H 'Content-Type: text/csv' "${auth[@]}" --data-binary "$csv" "$API/api/v1/customer-imports?routerId=$rid")
if [ "$(echo "$r" | jf 'd["imported"]')" = 2 ] && [ -n "$(echo "$r" | jf 'd["rows"][0]["pppoePassword"]')" ] && [ "$(echo "$r" | jf 'd["rows"][1]["subscriptionId"]')" = None ]; then
    echo "   ok   CSV import: 2 customers, one PENDING line on the router with its PPPoE password in this answer only"
else echo "   FAIL CSV import: $(echo "$r" | head -c 300)"; fail=1; fi
unset tmp otp ptoken stoken auth

echo "== services"
./stackctl status
./backupctl status | sed -n '1,12p'
"$MON/monitorctl" status
[ "$fail" = 0 ] && echo "STACK TEST PASSED" || { echo "STACK TEST FAILED"; exit 1; }
