#!/usr/bin/env bash
# Keeps the host's WireGuard hub and FreeRADIUS in step with the routers in the database (ADR 0013).
# Run by corebalt-routersync.timer every minute, in the stack directory beside stackctl:
#   - every router with a WireGuard public key is a peer of wg0, named <tenant code>-<router name>-<id
#     tail>, added or replaced with corebalt-wg-hub add-peer; never removed (routers have no delete), a
#     peer file that no router accounts for is only reported;
#   - when a router's RADIUS secret changed since the last run (a registration, an onboarding script, a
#     regenerated secret), FreeRADIUS is restarted (stackctl reload-radius: it reads radius_client_v3 at
#     start only), so nothing is left for the operator to do on the host when a router is onboarded.
# Reads as the container's postgres superuser over its socket, like backupctl. Skips quietly while
# nothing is deployed or the stack is down.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
[ -f images.env ] || { echo "routersync: nothing deployed"; exit 0; }
compose() {
    local files=(-f compose.yml)
    [ -f compose.override.yml ] && files+=(-f compose.override.yml)
    docker compose "${files[@]}" --env-file .env --env-file images.env "$@"
}
[ -n "$(compose ps -q --status running postgres 2>/dev/null)" ] || { echo "routersync: the stack is not running"; exit 0; }
HUB=${ROUTERSYNC_HUB:-corebalt-wg-hub}
PEER_DIR=${ROUTERSYNC_PEER_DIR:-/var/lib/wireguard/peers}
STATE=.routersync   # the newest router-secret change already loaded into FreeRADIUS
query() { compose exec -T -u postgres postgres psql -X -Atq -F "$(printf '\t')" -d isp -c "$1"; }

# 1. Hub peers: one per router with a key, named for the tenant and the router (lower-case letters,
# digits and hyphens, at most 40 characters: what add-peer accepts), unique by the id's last 4 digits.
PEERS_SQL="select left(lower(t.code) || '-' || trim(both '-' from regexp_replace(lower(r.name), '[^a-z0-9]+', '-', 'g')), 35)
           || '-' || right(replace(r.id::text, '-', ''), 4), r.wg_public_key, host(r.tunnel_ip_a)
           from network_router r join tenancy_tenant t on t.id = r.tenant_id
           where r.wg_public_key is not null order by 1"
rows=$(query "$PEERS_SQL")   # a failed query stops the run here, before any peer is judged missing
known=$(mktemp); trap 'rm -f "$known"' EXIT
added=0; unchanged=0; failed=0
while IFS="$(printf '\t')" read -r name key ip; do
    [ -n "$name" ] || continue
    echo "$name" >> "$known"
    conf="$PEER_DIR/$name.conf"
    if [ -s "$conf" ] && grep -qFx "PublicKey = $key" "$conf" && grep -qFx "AllowedIPs = $ip/32" "$conf"; then
        unchanged=$((unchanged + 1)); continue
    fi
    if "$HUB" add-peer "$name" "$key" "$ip/32" >/dev/null; then added=$((added + 1)); else failed=$((failed + 1)); echo "routersync: add-peer $name $ip failed" >&2; fi
done <<< "$rows"
orphans=0
for conf in "$PEER_DIR"/*.conf; do
    [ -e "$conf" ] || continue
    grep -qFx "$(basename "$conf" .conf)" "$known" || { orphans=$((orphans + 1)); echo "routersync: peer $(basename "$conf" .conf) is not a router in the database (kept; remove it by hand if it should go)"; }
done

# 2. FreeRADIUS: a router secret issued or changed since the last run means a restart, which loads
# every client again. A login during the seconds it takes is retried by the router.
latest=$(query "select coalesce(max(updated_at)::text, '') from network_radius_credential where router_id is not null")
previous=$(cat "$STATE" 2>/dev/null || true)
radius=unchanged
if [ -n "$latest" ] && [ "$latest" != "$previous" ]; then
    if ./stackctl reload-radius >/dev/null; then radius=reloaded; echo "$latest" > "$STATE"; else radius=RELOAD-FAILED; failed=$((failed + 1)); fi
fi
echo "routersync: peers $unchanged unchanged, $added added, $orphans not in the database; FreeRADIUS $radius"
[ "$failed" = 0 ]
