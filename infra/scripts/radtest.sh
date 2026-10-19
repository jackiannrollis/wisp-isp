#!/bin/sh
# Lab RADIUS login inside the FreeRADIUS container: radtest.sh [username] [password].
# Load the lab fixtures first (infra/lab/scripts/seed.sh); see radius/scripts/radtest.sh.
set -eu
cd "$(dirname "$0")/.."
docker compose exec freeradius corebalt-radtest "$@"
