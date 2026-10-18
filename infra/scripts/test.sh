#!/bin/sh
# Run every automated check: backend unit + Testcontainers tests, frontend lint, build and tests,
# compose validation. Needs JDK 25, Node 24 and Docker.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
(cd "$ROOT/backend" && ./mvnw -B verify)
(cd "$ROOT/frontend" && npm ci && npm run lint && npm run format:check && npm run build && npm run test)
(cd "$ROOT/infra" && docker compose --env-file .env.example config --quiet)
echo "All checks passed."
