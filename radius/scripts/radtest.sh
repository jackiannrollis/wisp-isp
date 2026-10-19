#!/bin/sh
# Local lab login test, run inside the FreeRADIUS container:
#   docker compose exec freeradius corebalt-radtest [username] [password]
#
# With the network-lab fixtures loaded (infra/lab/scripts/seed.ps1) the 127.0.0.1 NAS belongs to
# tenant CB and CB000001..CB000008 cover every access profile (see infra/lab/seed.sql), e.g.
#   CB000001  Access-Accept, FULL (5M/10M, pool full)
#   CB000004  Access-Accept, RESTRICTED (512k/512k, pool and address list restricted)
#   CB000006  Access-Reject, "Account suspended"
# Without the fixtures there is no NAS row for 127.0.0.1 and FreeRADIUS ignores the request.
set -eu

USERNAME="${1:-CB000001}"
PASSWORD="${2:-lab-password}"
SECRET="${RADIUS_LAB_SECRET:?RADIUS_LAB_SECRET is not set}"

# radtest exits 1 on Access-Reject.
exec radtest -t pap "$USERNAME" "$PASSWORD" 127.0.0.1:1812 0 "$SECRET"
