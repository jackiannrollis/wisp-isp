#!/bin/sh
# Local lab login test, run inside the FreeRADIUS container:
#   docker compose exec freeradius corebalt-radtest [username] [password]
#
# At step 1 the v_radius_auth view is a placeholder that returns no rows, so the EXPECTED result
# is "Received Access-Reject" with Reply-Message "Unknown user". That proves FreeRADIUS is up, the
# lab client is accepted and rlm_sql queried PostgreSQL. Real logins arrive with steps 2 and 3.
set -eu

USERNAME="${1:-CB000001}"
PASSWORD="${2:-lab-password}"
SECRET="${RADIUS_LAB_SECRET:?RADIUS_LAB_SECRET is not set}"

# radtest exits 1 on Access-Reject, which is the expected outcome until v_radius_auth is real.
exec radtest -t pap "$USERNAME" "$PASSWORD" 127.0.0.1:1812 0 "$SECRET"
