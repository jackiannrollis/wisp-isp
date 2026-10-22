#!/usr/bin/env bash
# Local test of the staging/production stack (infra/stack/compose.yml), exactly as hosts run it except:
# local images, Caddy's internal CA instead of ACME, *.localhost names, named volumes, fake secrets,
# ports 8081/8443 and RADIUS on 127.0.0.1:11812. Loads the lab seed, then checks the routing from the
# outside. Leaves the stack running for a look in the browser (https://admin.corebalt.localhost:8443);
# "test.sh down" removes it with its volumes.
set -euo pipefail
cd "$(dirname "$0")"
# Git Bash rewrites /path arguments for native programs; docker needs them as they are, curl does not.
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }
PROJECT=corebalt-stacktest
ENV_FILE=.env.test
compose() { docker compose -p "$PROJECT" -f compose.yml -f test.override.yml --env-file "$ENV_FILE" "$@"; }
# Every *.corebalt.localhost name goes to this machine, whatever the resolver thinks of .localhost.
curl() { command curl --connect-to ::127.0.0.1: "$@"; }

if [ "${1:-}" = down ]; then
    [ -f "$ENV_FILE" ] && compose down -v --remove-orphans
    rm -f "$ENV_FILE"
    exit 0
fi

if [ -z "${STACKTEST_SKIP_BUILD:-}" ]; then
    echo "== images"
    docker build -q -t corebalt-stacktest/backend ../../backend >/dev/null
    docker build -q -t corebalt-stacktest/freeradius ../../radius >/dev/null
    docker build -q -t corebalt-stacktest/web -f ../web/Dockerfile ../.. >/dev/null
fi

# The Cloudflare plugin checks the token's shape (40 of [A-Za-z0-9_-]) when it loads: a fake of that shape.
echo "== Caddyfile parses in dns mode with both providers (the certificate itself needs a real zone)"
for provider in cloudflare hetzner; do
    docker run --rm -e PLATFORM_DOMAIN=example.test -e TLS_MODE=dns -e ACME_EMAIL=ops@example.test         -e ACME_DNS_PROVIDER=$provider -e ACME_DNS_API_TOKEN=fake0000000000000000000000000000000000Tk corebalt-stacktest/web         caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1 && echo "   ok   $provider"         || { echo "   FAIL Caddyfile with dns $provider"; exit 1; }
done

if [ ! -f "$ENV_FILE" ]; then
    fake() { echo "stacktest-$(openssl rand -hex 12)"; }
    key=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null \
        | openssl pkcs8 -topk8 -nocrypt -outform DER | openssl base64 -A)
    cat > "$ENV_FILE" <<EOF
# Throwaway values for the local stack test only.
PLATFORM_DOMAIN=corebalt.localhost
TLS_MODE=internal
ACME_EMAIL=ops@corebalt.localhost
HTTP_PORT=8081
HTTPS_PORT=8443
WG_HUB_IP=127.0.0.1
RADIUS_AUTH_PORT=11812
RADIUS_ACCT_PORT=11813
BACKEND_IMAGE=corebalt-stacktest/backend
FREERADIUS_IMAGE=corebalt-stacktest/freeradius
WEB_IMAGE=corebalt-stacktest/web
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
EOF
fi

echo "== up"
compose up -d --wait --wait-timeout 300
echo "== lab seed"
set -a; . "./$ENV_FILE"; set +a
LAB_SECRET=stacktest-lab-secret CHR_TUNNEL_IP=10.200.0.11 PGPASSWORD="$ISP_DB_OWNER_PASSWORD" \
    compose exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres \
    psql -h localhost -U isp_owner -d isp -q -v ON_ERROR_STOP=1 < ../lab/seed.sql

echo "== routing, from outside"
fail=0
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
curl -sk -D - -o /dev/null "https://admin.corebalt.localhost:8443/$bundle" | grep -qi 'cache-control: public, max-age=31536000, immutable'     && echo "   ok   hashed bundle immutable ($bundle)" || { echo "   FAIL bundle cache header ($bundle)"; fail=1; }
echo "== both apps in a browser through the front (sign-in, a page each; any console error fails, CSP included)"
if [ -d ../../frontend/node_modules/@playwright ]; then
    (cd ../../frontend && node e2e/stack-smoke.mjs https://corebalt.localhost:8443) || fail=1
else
    echo "   skipped: frontend/node_modules missing (npm ci in frontend/ first)"
fi
echo "== services"
compose ps --format '{{.Service}} {{.Status}}'
[ "$fail" = 0 ] && echo "STACK TEST PASSED" || { echo "STACK TEST FAILED"; exit 1; }
