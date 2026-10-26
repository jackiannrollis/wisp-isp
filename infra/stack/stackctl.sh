#!/usr/bin/env bash
# The stack's control script on a host, installed by roles/stack as /srv/corebalt/stack/stackctl and
# used by the corebalt-stack unit and by the deploy and rollback playbooks (decisions.md 2026-10-22).
# It runs in the stack directory, with .env (the host's settings and secrets) and images.env (the
# deployed release: image digests, RELEASE, DEPLOYED_AT), plus compose.override.yml when present (the
# local test only).
#
#   stackctl up                start or update every service to images.env (the unit's ExecStart)
#   stackctl stop              stop every service (the unit's ExecStop)
#   stackctl deploy <file>     roll a new images.env out: pull first, then one service at a time behind
#                              its health gate; a failure rolls the previous release back
#   stackctl rollback          roll the previous release (images.env.previous) back
#   stackctl status            what is deployed and running
#   stackctl reload-radius     restart FreeRADIUS so it reads the routers again: it loads radius_client_v3
#                              at start only, so run it after a router is registered or its secret replaced
#                              (infra/ops/runbooks/pilot-onboarding.md); sessions stay up, a login during
#                              the restart is retried by the router
# Backups, the restore drill and disaster recovery: backupctl, beside this script.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

# docs/02, docs/08: one API instance at a time, the other keeps serving; then the rest.
ROLL_ORDER="api-1 api-2 tunnel-worker freeradius web"
WAIT="${STACK_WAIT:-300}"

compose_with() { # <images.env> <compose args...>
    local images=$1; shift
    local files=(-f compose.yml)
    [ -f compose.override.yml ] && files+=(-f compose.override.yml)
    docker compose "${files[@]}" --env-file .env --env-file "$images" "$@"
}
compose() { compose_with images.env "$@"; }
release() { sed -n 's/^RELEASE=//p' "${1:-images.env}" 2>/dev/null | head -1; }

roll() { # each service in order, recreated only when its image changed; stops at the first unhealthy one
    local svc out
    # The foundation first: on a first deploy it is not running yet. rabbitmq and redis are pinned in
    # compose.yml; postgres comes from images.env like the application images, so a release that
    # changes it restarts the database here (seconds; the APIs reconnect).
    compose up -d --wait --wait-timeout "$WAIT" postgres rabbitmq redis >/dev/null 2>&1         || { echo "   postgres, rabbitmq or redis FAILED to come up" >&2; return 1; }
    echo "   postgres, rabbitmq, redis ok"
    # The monitoring role for postgres-exporter (infra/monitoring): pg_monitor only, no table reads
    # (T1); created on a fresh cluster and kept in step with .env afterwards. Best effort: a release is
    # not blocked by it, and CorebaltPostgresDown reports an exporter that cannot connect.
    if grep -q '^ISP_DB_MONITOR_PASSWORD=.' .env; then
        if out=$(compose exec -T -u postgres postgres psql -X -q -v ON_ERROR_STOP=1 -d isp 2>&1 <<'SQL'
\getenv pw ISP_DB_MONITOR_PASSWORD
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'isp_monitor') THEN CREATE ROLE isp_monitor; END IF; END $$;
ALTER ROLE isp_monitor LOGIN PASSWORD :'pw';
GRANT pg_monitor TO isp_monitor;
GRANT CONNECT ON DATABASE isp TO isp_monitor;
SQL
        ); then echo "   monitoring role ok"
        else echo "   monitoring role WARNING: $(echo "$out" | grep -v '^ *$' | tail -1)" >&2; fi
    fi
    # The backup repository's stanza, created on the first deploy and a no-op afterwards. Best effort:
    # a backup problem must not block a release; the nightly backup and its alert report it.
    if [ -x ./backupctl ] && [ -s pgbackrest.conf ]; then
        if out=$(./backupctl stanza 2>&1); then echo "   backups ok (stanza present, archiving checked)"
        else echo "   backups WARNING: $(echo "$out" | grep -v '^ *$' | tail -1) (backupctl stanza)" >&2; fi
    fi
    for svc in $ROLL_ORDER; do
        if compose up -d --no-deps --wait --wait-timeout "$WAIT" "$svc" >/dev/null 2>&1; then
            echo "   $svc ok"
        else
            echo "   $svc FAILED its health gate" >&2
            compose logs --no-color --tail 40 "$svc" >&2 || true
            return 1
        fi
    done
}

case "${1:-}" in
    up)
        [ -f images.env ] || { echo "stackctl: nothing deployed yet (no images.env); deploy a release first" >&2; exit 1; }
        compose up -d --wait --wait-timeout "$WAIT" --remove-orphans ;;
    stop)
        compose stop ;;
    status)
        [ -f images.env ] || { echo "not deployed"; exit 0; }
        echo "release $(release)$( [ -f images.env.previous ] && echo " (previous: $(release images.env.previous))")"
        compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Image}}' ;;
    deploy)
        new=${2:?stackctl deploy <images.env to roll out>}
        [ -s "$new" ] || { echo "stackctl: $new is missing or empty" >&2; exit 2; }
        grep -q '^RELEASE=' "$new" || { echo "stackctl: $new has no RELEASE line" >&2; exit 2; }
        if [ -f images.env ] && cmp -s <(grep '_IMAGE=' "$new") <(grep '_IMAGE=' images.env); then
            echo "stackctl: release $(release) is already deployed; nothing to do"
            rm -f "$new"
            exit 0
        fi
        echo "== pulling release $(release "$new")"
        # Before anything changes. A local-only image (the test) cannot be pulled, so a pull failure is
        # not final here: a missing image fails the service's health gate below and is rolled back.
        compose_with "$new" pull --quiet --ignore-pull-failures postgres api-1 api-2 tunnel-worker freeradius web 2>&1 \
            | grep -v '^$' | sed 's/^/   /' || true
        [ -f images.env ] && cp images.env images.env.previous
        mv "$new" images.env
        echo "== rolling out $(release)"
        if roll; then
            echo "stackctl: release $(release) deployed"
            exit 0
        fi
        if [ -f images.env.previous ]; then
            cp images.env images.env.failed
            cp images.env.previous images.env
            echo "== rolling back to $(release)" >&2
            roll || { echo "stackctl: ROLLBACK FAILED; the stack needs hands (stackctl status)" >&2; exit 3; }
            echo "stackctl: release $(release images.env.failed) failed its health gate; back on $(release)" >&2
        else
            # Nothing is deployed, then: the unit stays down and the next deploy starts afresh.
            mv images.env images.env.failed
            echo "stackctl: the first deploy failed; nothing to roll back to (docker compose logs; the release is kept as images.env.failed)" >&2
        fi
        exit 1 ;;
    rollback)
        [ -f images.env.previous ] || { echo "stackctl: no previous release to roll back to" >&2; exit 1; }
        from=$(release)
        mv images.env images.env.rolled-back
        mv images.env.previous images.env
        mv images.env.rolled-back images.env.previous
        echo "== rolling back from $from to $(release)"
        roll || { echo "stackctl: ROLLBACK FAILED; the stack needs hands (stackctl status)" >&2; exit 3; }
        echo "stackctl: back on release $(release); $from is now the previous one" ;;
    reload-radius)
        [ -f images.env ] || { echo "stackctl: nothing deployed yet (no images.env)" >&2; exit 1; }
        compose restart freeradius >/dev/null 2>&1 || { echo "stackctl: FreeRADIUS did not restart (docker compose logs freeradius)" >&2; exit 1; }
        echo "stackctl: FreeRADIUS restarted; it read the routers and their secrets (radius_client_v3) at start" ;;
    *)
        sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' >&2
        exit 2 ;;
esac
