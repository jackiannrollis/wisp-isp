#!/bin/sh
# Break-glass reset of a platform admin (ADR 0010): reset-platform-admin.sh <email>
# Runs inside the backend container (never over HTTP). Prints a temporary password once, clears TOTP
# and recovery codes, ends every session and writes the platform audit log with ISP_OPERATOR (set it
# to your name; the OS user otherwise).
set -eu
[ $# -eq 1 ] || { echo "usage: $0 <email>" >&2; exit 2; }
cd "$(dirname "$0")/.."
export ISP_OPERATOR="${ISP_OPERATOR:-${USER:-unknown}}"
docker compose exec -e ISP_OPERATOR backend java -jar /app/app.jar reset-platform-admin "$1"
