#!/usr/bin/env bash
# Metrics no exporter gives, written as text files for node_exporter's textfile collector into the
# metrics directory (the one backupctl uses, /srv/corebalt/metrics on a host): run every minute by
# corebalt-probes.timer as root; installed by roles/monitoring as /srv/corebalt/monitoring/probes.
#
#   probes radius      FreeRADIUS's counters and the Status-Server round trip, asked from inside the
#                      running freeradius container (the status site listens on its loopback only,
#                      radius/raddb/sites-available/corebalt-status); corebalt_radius.prom
#   probes wireguard   the hub's peers from wg0: last handshake, bytes in and out, named after the peer
#                      files of corebalt-wg-hub; corebalt_wireguard.prom
#   probes all         both (the timer)
#
# Alert rules on these: infra/monitoring/rules/radius.yml and tunnels.yml.
set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")"
export MSYS_NO_PATHCONV=1
STACK_DIR=${STACK_DIR:-$(cd ../stack 2>/dev/null && (pwd -W 2>/dev/null || pwd))}
envval() { sed -n "s/^$1=//p" "$STACK_DIR/.env" 2>/dev/null | head -1; }
METRICS_DIR=$(envval METRICS_DIR); METRICS_DIR=${METRICS_DIR:-/srv/corebalt/metrics}
case "$METRICS_DIR" in /*|[A-Za-z]:*) ;; *) METRICS_DIR="$STACK_DIR/$METRICS_DIR" ;; esac
WG_INTERFACE=${WG_INTERFACE:-wg0}
WG_PEERS_DIR=${WG_PEERS_DIR:-/var/lib/wireguard/peers}

write_metrics() { # <file name>; stdin: the metrics. Atomic, so node_exporter never reads a half file.
    mkdir -p "$METRICS_DIR"
    local tmp="$METRICS_DIR/.$1.tmp"
    cat > "$tmp" && chmod 0644 "$tmp" && mv -f "$tmp" "$METRICS_DIR/$1"
}

stack_compose() {
    local files=(-f compose.yml)
    [ -f "$STACK_DIR/compose.override.yml" ] && files+=(-f compose.override.yml)
    (cd "$STACK_DIR" && docker compose "${files[@]}" --env-file .env --env-file images.env "$@")
}

radius() {
    [ -f "$STACK_DIR/images.env" ] || return 0    # nothing deployed yet: no FreeRADIUS to ask
    local out rc
    # The secret goes to radclient through a file (never an argument); the round trip is timed inside
    # the container, so docker's own latency is not counted. Every counter is cumulative since start.
    out=$(stack_compose exec -T freeradius sh -c '
        umask 077
        printf "%s" "$RADIUS_STATUS_SECRET" > /tmp/.status-secret
        s=$(date +%s%N)
        printf "Message-Authenticator = 0x00\nFreeRADIUS-Statistics-Type = All\n" \
            | radclient -x -r 1 -t 3 -S /tmp/.status-secret 127.0.0.1:18121 status 2>&1
        rc=$?
        e=$(date +%s%N)
        rm -f /tmp/.status-secret
        echo "RTT_NS $((e - s))"
        exit $rc' 2>&1)
    rc=$?
    if [ $rc -ne 0 ] || ! echo "$out" | grep -q 'FreeRADIUS-Total-Access-Requests'; then
        printf '# HELP corebalt_radius_up Whether FreeRADIUS answered the Status-Server probe.\n# TYPE corebalt_radius_up gauge\ncorebalt_radius_up 0\n' | write_metrics corebalt_radius.prom
        echo "probes: FreeRADIUS status probe failed: $(echo "$out" | grep -v '^\s*$' | tail -1)" >&2
        return 1
    fi
    {
        printf '# HELP corebalt_radius_up Whether FreeRADIUS answered the Status-Server probe.\n# TYPE corebalt_radius_up gauge\ncorebalt_radius_up 1\n'
        echo "$out" | awk '
            /^[ \t]*FreeRADIUS-Total-/ { n = $1; sub(/^FreeRADIUS-Total-/, "", n); gsub(/-/, "_", n); n = tolower(n)
                                         printf "# TYPE corebalt_radius_%s_total counter\ncorebalt_radius_%s_total %s\n", n, n, $3; next }
            /^[ \t]*FreeRADIUS-Queue-Len-/ { n = $1; sub(/^FreeRADIUS-Queue-Len-/, "", n); printf "corebalt_radius_queue_length{queue=\"%s\"} %s\n", tolower(n), $3; next }
            /^RTT_NS / { printf "# TYPE corebalt_radius_status_round_trip_seconds gauge\ncorebalt_radius_status_round_trip_seconds %.6f\n", $2 / 1000000000 }'
    } | write_metrics corebalt_radius.prom
}

wireguard() {
    command -v wg >/dev/null 2>&1 || return 0    # not a hub host (or the local test)
    local dump
    if ! dump=$(wg show "$WG_INTERFACE" dump 2>/dev/null); then
        printf '# HELP corebalt_wireguard_up Whether the hub interface exists.\n# TYPE corebalt_wireguard_up gauge\ncorebalt_wireguard_up 0\n' | write_metrics corebalt_wireguard.prom
        return 1
    fi
    # Peer names from the hub's peer files (name.conf holding PublicKey = ...); unnamed peers are shown by key.
    local names=""
    if [ -d "$WG_PEERS_DIR" ]; then
        names=$(grep -H '^PublicKey = ' "$WG_PEERS_DIR"/*.conf 2>/dev/null | sed 's#^.*/\([^/]*\)\.conf:PublicKey = #\1 #')
    fi
    {
        printf '# HELP corebalt_wireguard_up Whether the hub interface exists.\n# TYPE corebalt_wireguard_up gauge\ncorebalt_wireguard_up 1\n'
        echo "$dump" | awk -v names="$names" '
            BEGIN { n = split(names, lines, "\n"); for (i = 1; i <= n; i++) { split(lines[i], kv, " "); name[kv[2]] = kv[1] } }
            NF == 8 { peers++; p = (($1 in name) ? name[$1] : substr($1, 1, 8))
                      hs[p] = $5; ips[p] = $4; rx[p] = $6; tx[p] = $7 }
            END {
                printf "# TYPE corebalt_wireguard_peers gauge\ncorebalt_wireguard_peers %d\n", peers + 0
                print "# TYPE corebalt_wireguard_peer_latest_handshake_seconds gauge"
                for (p in hs) printf "corebalt_wireguard_peer_latest_handshake_seconds{peer=\"%s\",allowed_ips=\"%s\"} %s\n", p, ips[p], hs[p]
                print "# TYPE corebalt_wireguard_peer_receive_bytes_total counter"
                for (p in rx) printf "corebalt_wireguard_peer_receive_bytes_total{peer=\"%s\"} %s\n", p, rx[p]
                print "# TYPE corebalt_wireguard_peer_transmit_bytes_total counter"
                for (p in tx) printf "corebalt_wireguard_peer_transmit_bytes_total{peer=\"%s\"} %s\n", p, tx[p]
            }'
    } | write_metrics corebalt_wireguard.prom
}

case "${1:-}" in
    radius) radius ;;
    wireguard) wireguard ;;
    all) rc=0; radius || rc=1; wireguard || rc=1; exit $rc ;;
    *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
