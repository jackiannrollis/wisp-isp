#!/usr/bin/env bash
# Daraja sandbox round-trip (docs/05, "Sandbox round-trip"); see daraja-sandbox.ps1 for the steps.
# Skips with a message unless infra/.env has the DARAJA_SANDBOX_* keys and an https CALLBACK_BASE_URL.
# Secrets never go on a command line: curl reads its headers from stdin.
set -euo pipefail
cd "$(dirname "$0")/.."
env_value() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- || true; }

KEY="$(env_value DARAJA_SANDBOX_CONSUMER_KEY)"
SECRET="$(env_value DARAJA_SANDBOX_CONSUMER_SECRET)"
PASSKEY="$(env_value DARAJA_SANDBOX_PASSKEY)"
BASE="$(env_value CALLBACK_BASE_URL)"
SHORTCODE="$(env_value DARAJA_SANDBOX_SHORTCODE)"; SHORTCODE="${SHORTCODE:-174379}"
MSISDN="$(env_value DARAJA_SANDBOX_TEST_MSISDN)"; MSISDN="${MSISDN:-254708374149}"
PORT="$(env_value BACKEND_PORT)"; API="http://localhost:${PORT:-8080}/api/v1"

missing=()
[ -n "$KEY" ] || missing+=(DARAJA_SANDBOX_CONSUMER_KEY)
[ -n "$SECRET" ] || missing+=(DARAJA_SANDBOX_CONSUMER_SECRET)
[ -n "$PASSKEY" ] || missing+=(DARAJA_SANDBOX_PASSKEY)
[[ "$BASE" == https://* ]] || missing+=("CALLBACK_BASE_URL (https tunnel to http://localhost:8088)")
if [ ${#missing[@]} -gt 0 ]; then
  echo "SKIPPED: Daraja sandbox round-trip not run. Set in infra/.env: ${missing[*]}."
  echo "Then restart the backend (infra/scripts/up.sh) so it uses CALLBACK_BASE_URL, and run this again."
  exit 0
fi

# Lab admin from infra/lab/seed.sql (local stack only; obvious fake password, role without 2FA).
JWT="$(printf '%s' '{"tenant":"CB","email":"lab-admin@corebalt.example.test","password":"lab-admin-password"}' \
  | curl -sf -H 'Content-Type: application/json' --data-binary @- "$API/auth/login" \
  | sed -E 's/.*"accessToken" *: *"([^"]+)".*/\1/')"
[ -n "$JWT" ] || { echo "lab admin sign-in failed; load infra/lab/seed.sql first" >&2; exit 1; }
api() { curl -sf -K - "$@" <<<"header = \"Authorization: Bearer $JWT\""; }

echo "== 1. Tenant CB: sandbox settings"
printf '{"shortcode":"%s","shortcodeType":"PAYBILL","sandbox":true,"consumerKey":"%s","consumerSecret":"%s","passkey":"%s"}' \
  "$SHORTCODE" "$KEY" "$SECRET" "$PASSKEY" | api -X PUT -H 'Content-Type: application/json' --data-binary @- "$API/settings/mpesa" >/dev/null

echo "== 2. Register C2B URLs with the sandbox"
api -X POST "$API/settings/mpesa/register-urls" >/dev/null

echo "== 3. Simulate a C2B payment of KES 2,500 to CB000004"
BASIC="$(printf '%s:%s' "$KEY" "$SECRET" | base64 | tr -d '\n')"
TOKEN="$(curl -sf -K - "https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials" \
  <<<"header = \"Authorization: Basic $BASIC\"" | sed -E 's/.*"access_token" *: *"([^"]+)".*/\1/')"
printf '{"ShortCode":"%s","CommandID":"CustomerPayBillOnline","Amount":2500,"Msisdn":"%s","BillRefNumber":"CB000004"}' \
  "$SHORTCODE" "$MSISDN" | curl -sf -K <(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN") \
  -H 'Content-Type: application/json' --data-binary @- https://sandbox.safaricom.co.ke/mpesa/c2b/v1/simulate >/dev/null

for _ in $(seq 1 60); do
  sleep 3
  if api "$API/subscriptions/01900000-0000-7000-8000-0000000b0004" | grep -q '"accessProfile":"FULL"'; then
    echo "   CB000004 restored (FULL)"; break
  fi
done

echo "== 4. STK Push KES 1 to the sandbox test number for CB000001"
STK="$(printf '{"customerId":"01900000-0000-7000-8000-0000000c0001","amount":{"amount":100,"currency":"KES"},"phone":"%s"}' "$MSISDN" \
  | api -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: sandbox-$(date +%s)" --data-binary @- "$API/payments/stk")"
ID="$(printf '%s' "$STK" | sed -E 's/.*"id":"([^"]+)".*/\1/')"
for _ in $(seq 1 72); do
  sleep 5
  STATUS="$(api "$API/payments/stk/$ID" | sed -E 's/.*"status":"([^"]+)".*/\1/')"
  [ "$STATUS" != "PENDING" ] && break
done
echo "   STK request: $STATUS"

echo "== 5. CoA to the router"
echo "   SKIPPED: the network module's CoA/Disconnect consumer is not built yet; Session-Timeout bounds the delay."
