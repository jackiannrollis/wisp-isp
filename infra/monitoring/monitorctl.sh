#!/usr/bin/env bash
# The monitoring project's control script on a host, installed by roles/monitoring as
# /srv/corebalt/monitoring/monitorctl and used by the corebalt-monitoring unit and the monitoring
# playbook (infra/ops/runbooks/monitoring.md). It runs in the monitoring directory: compose.yml, .env,
# the configuration files, plus compose.override.yml when present (the local test only).
#
#   monitorctl up        start or update every service (the unit's ExecStart), then re-read the configurations
#   monitorctl stop      stop every service (the unit's ExecStop)
#   monitorctl reload    re-read prometheus.yml, the rules, alertmanager.yml, blackbox.yml and the Alloy
#                        configuration without a restart (SIGHUP); Loki re-reads its rules by itself
#   monitorctl check     validate every configuration file with the images' own tools, and run the rule tests
#   monitorctl status    services, scrape targets that are down, alerts firing
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
# Git Bash on Windows (the local test): docker needs D:/... paths and must not rewrite /path arguments.
DIR=$(pwd -W 2>/dev/null || pwd)
export MSYS_NO_PATHCONV=1

compose() {
    local files=(-f compose.yml)
    [ -f compose.override.yml ] && files+=(-f compose.override.yml)
    docker compose "${files[@]}" --env-file .env "$@"
}
image_of() { awk -v svc="  $1:" '$0 == svc {f=1} f && /^ *image:/ {print $2; exit}' compose.yml; }
PY=$(command -v python3 || command -v python)

case "${1:-}" in
    up)
        compose up -d --wait --wait-timeout "${MONITORING_WAIT:-240}" --remove-orphans
        "$0" reload ;;
    stop)
        compose stop ;;
    reload)
        # Only the services that read a file at SIGHUP; a service that is not running is skipped.
        for svc in prometheus alertmanager blackbox-exporter alloy; do
            compose kill -s HUP "$svc" >/dev/null 2>&1 || true
        done ;;
    check)
        fail=0
        run() { # <label> <docker run args...>
            local label=$1; shift
            if out=$(docker run --rm "$@" 2>&1); then echo "   ok   $label"
            else echo "   FAIL $label"; echo "$out" | tail -40; fail=1; fi
        }
        compose config -q && echo "   ok   compose file" || { echo "   FAIL compose file"; fail=1; }
        run "prometheus.yml and the rule files (promtool check config)" \
            -v "$DIR:/m:ro" --entrypoint promtool "$(image_of prometheus)" check config /m/prometheus.yml
        run "rule unit tests (promtool test rules)" \
            -v "$DIR:/m:ro" -w /m/tests --entrypoint sh "$(image_of prometheus)" -c 'promtool test rules *.yml'
        [ -f alertmanager.yml ] && run "alertmanager.yml (amtool check-config)" \
            -v "$DIR:/m:ro" --entrypoint amtool "$(image_of alertmanager)" check-config /m/alertmanager.yml
        run "blackbox.yml" \
            -v "$DIR/blackbox.yml:/c.yml:ro" "$(image_of blackbox-exporter)" --config.check --config.file=/c.yml
        run "Loki configuration (loki -verify-config)" \
            -v "$DIR/loki:/etc/loki:ro" -e LOKI_RETENTION=720h "$(image_of loki)" -config.file=/etc/loki/config.yml -config.expand-env=true -verify-config
        run "Alloy configuration (alloy validate)" \
            -v "$DIR/alloy/config.alloy:/c.alloy:ro" "$(image_of alloy)" validate /c.alloy
        exit $fail ;;
    status)
        compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Image}}'
        echo "== scrape targets not up"
        curl -sf --max-time 5 http://127.0.0.1:9090/api/v1/targets | "$PY" -I -c '
import json, sys
down = [t for t in json.load(sys.stdin)["data"]["activeTargets"] if t["health"] != "up"]
for t in down:
    print("   %s %s: %s" % (t["labels"].get("job"), t["labels"].get("instance"), (t.get("lastError") or t["health"])[:120]))
print("   (none)" if not down else "   %d target(s) down" % len(down))' 2>/dev/null || echo "   (Prometheus not answering on 127.0.0.1:9090)"
        echo "== alerts firing"
        curl -sf --max-time 5 'http://127.0.0.1:9093/api/v2/alerts?active=true&silenced=false&inhibited=false' | "$PY" -I -c '
import json, sys
alerts = [a for a in json.load(sys.stdin) if a["labels"].get("alertname") != "Watchdog"]
for a in sorted(alerts, key=lambda a: (a["labels"].get("severity", ""), a["labels"].get("alertname", ""))):
    l = a["labels"]
    print("   %-8s %s %s" % (l.get("severity", ""), l.get("alertname", ""), a["annotations"].get("summary", "")))
print("   (none besides the Watchdog)" if not alerts else "   %d alert(s)" % len(alerts))' 2>/dev/null || echo "   (Alertmanager not answering on 127.0.0.1:9093)" ;;
    *)
        sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//' >&2
        exit 2 ;;
esac
