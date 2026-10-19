#!/bin/sh
# Creates or rotates the access-token signing key (ADR 0010) in infra/.env.
#   jwt-key.sh            create a key if ISP_JWT_PRIVATE_KEY is empty (does nothing otherwise)
#   jwt-key.sh --rotate   new key for new tokens; the old public key moves to ISP_JWT_RETIRED_KEYS,
#                         so tokens already issued stay valid until they expire. Remove retired
#                         keys after a day.
# The private key is written only to infra/.env (git-ignored), never printed. Needs openssl.
set -eu
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "infra/.env missing: copy infra/.env.example first" >&2; exit 1; }

value() { sed -n "s/^$1=//p" .env | head -n 1; }
set_value() {
    if grep -q "^$1=" .env; then
        tmp=$(mktemp)
        awk -v name="$1" -v value="$2" 'index($0, name "=") == 1 { print name "=" value; next } { print }' .env > "$tmp"
        cat "$tmp" > .env && rm -f "$tmp"
    else
        printf '%s=%s\n' "$1" "$2" >> .env
    fi
}

current=$(value ISP_JWT_PRIVATE_KEY)
case "$current" in change-me*) current= ;; esac
if [ -n "$current" ] && [ "${1:-}" != "--rotate" ]; then
    echo "ISP_JWT_PRIVATE_KEY is already set (kid $(value ISP_JWT_KID)); use --rotate to replace it."
    exit 0
fi

retired=$(value ISP_JWT_RETIRED_KEYS)
if [ -n "$current" ]; then
    public=$(printf '%s' "$current" | openssl base64 -d -A | openssl pkey -inform DER -pubout -outform DER | openssl base64 -A)
    entry="$(value ISP_JWT_KID)=$public"
    retired=${retired:+$retired,}$entry
fi

kid="k$(date -u +%Y%m%d%H%M)"
key=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null \
    | openssl pkcs8 -topk8 -nocrypt -outform DER | openssl base64 -A)
set_value ISP_JWT_KID "$kid"
set_value ISP_JWT_PRIVATE_KEY "$key"
set_value ISP_JWT_RETIRED_KEYS "$retired"
echo "Signing key $kid written to infra/.env. Restart the backend: docker compose up -d backend"
