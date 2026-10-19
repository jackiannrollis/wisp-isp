#!/usr/bin/env bash
# Africa's Talking sandbox round-trip (docs/04, "SMS sandbox round-trip"); see at-sandbox.ps1 for the
# steps. Skips with a message unless infra/.env has the AT_SANDBOX_* values and an https
# CALLBACK_BASE_URL. Secrets never go on a command line: curl reads headers from stdin.
set -euo pipefail
cd "$(dirname "$0")/.."
env_value() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- || true; }

KEY="$(env_value AT_SANDBOX_API_KEY)"
SENDER="$(env_value AT_SANDBOX_SENDER_ID)"
NUMBER="$(env_value AT_SANDBOX_TEST_NUMBER)"
BASE="$(env_value CALLBACK_BASE_URL)"
PORT="$(env_value BACKEND_PORT)"; API="http://localhost:${PORT:-8080}/api/v1"

missing=()
[ -n "$KEY" ] || missing+=(AT_SANDBOX_API_KEY)
[ -n "$SENDER" ] || missing+=(AT_SANDBOX_SENDER_ID)
[[ "$NUMBER" =~ ^\+254[17][0-9]{8}$ ]] || missing+=("AT_SANDBOX_TEST_NUMBER (E.164, open in the AT simulator)")
[[ "$BASE" == https://* ]] || missing+=("CALLBACK_BASE_URL (https tunnel to http://localhost:8088)")
if [ ${#missing[@]} -gt 0 ]; then
  echo "SKIPPED: Africa's Talking sandbox round-trip not run. Set in infra/.env: ${missing[*]}."
  exit 0
fi

b64url() { printf '%s' "$1" | base64 | tr -d '=\n' | tr '+/' '-_'; }
JWT="$(b64url '{"alg":"none"}').$(b64url '{"sub":"at-sandbox","tenant_id":"01900000-0000-7000-8000-000000000001","permissions":["settings.secrets","settings.notifications","payments.record_manual"]}')."
api() { curl -sf -K - "$@" <<<"header = \"Authorization: Bearer $JWT\""; }
CUSTOMER=01900000-0000-7000-8000-0000000c0001

echo "== 1. Tenant CB: Africa's Talking sandbox settings"
printf '{"atUsername":"sandbox","senderId":"%s","sandbox":true,"apiKey":"%s","windowStart":"00:00","windowEnd":"23:59"}' "$SENDER" "$KEY" \
  | api -X PUT -H 'Content-Type: application/json' --data-binary @- "$API/settings/notifications" >/dev/null
echo "   Set the delivery report URL in the AT sandbox dashboard (GET $API/settings/notifications shows it)."

echo "== 2. CB000001 uses the simulator number"
NAME="$(api "$API/customers/$CUSTOMER" | sed -E 's/.*"name":"([^"]+)".*/\1/')"
printf '{"name":"%s","phone":"%s"}' "$NAME" "$NUMBER" | api -X PUT -H 'Content-Type: application/json' --data-binary @- "$API/customers/$CUSTOMER" >/dev/null

echo "== 3. A cash payment for CB000001"
printf '{"customerId":"%s","amount":{"amount":250000,"currency":"KES"},"channel":"CASH","reference":"ATSBX-%s"}' "$CUSTOMER" "$(date +%s)" \
  | api -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: at-sandbox-$(date +%s)" --data-binary @- "$API/payments/manual" >/dev/null

for _ in $(seq 1 60); do
  sleep 3
  STATUS="$(api "$API/notifications?customerId=$CUSTOMER&size=1" | sed -E 's/.*"status":"([A-Z_]+)".*/\1/')"
  case "$STATUS" in DELIVERED|FAILED|DEAD) break ;; esac
done
echo "   last message: $STATUS"
