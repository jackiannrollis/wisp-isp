#!/bin/sh
# Lab RADIUS login test inside the FreeRADIUS container (expects Access-Reject at step 1).
set -eu
cd "$(dirname "$0")/.."
docker compose exec freeradius corebalt-radtest "$@"
