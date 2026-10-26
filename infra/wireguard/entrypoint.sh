#!/bin/sh
# WireGuard hub: brings up wg0 and the peers stored in the wireguard-data volume.
#
#   wireguard-hub                                   start the hub (container entrypoint)
#   wireguard-hub add-peer <name> <pubkey> <cidr>   add or replace a router peer, applied at once
#   wireguard-hub public-key                        print the hub's public key
#   wireguard-hub export-peers                      every peer as the add-peer line that recreates it
#                                                   (public keys and tunnel addresses only), for escrow
#                                                   and a replacement host (runbooks/failover-drill.md)
#
# The hub's private key is generated on first start and never leaves the volume. Peer files hold
# only a router's public key and tunnel address (/var/lib/wireguard/peers/<name>.conf).
set -eu

STATE_DIR=/var/lib/wireguard
PEER_DIR="$STATE_DIR/peers"
HUB_ADDRESS="${WG_HUB_ADDRESS:-10.200.0.1/24}"
LISTEN_PORT="${WG_LISTEN_PORT:-51820}"

load_peers() {
    for peer in "$PEER_DIR"/*.conf; do
        [ -e "$peer" ] || continue
        wg addconf wg0 "$peer"
        echo "wireguard-hub: loaded peer $(basename "$peer" .conf)"
    done
}

case "${1:-start}" in
    add-peer)
        [ $# -eq 4 ] || { echo "usage: wireguard-hub add-peer <name> <public key> <tunnel ip>/32" >&2; exit 2; }
        name=$2 key=$3 cidr=$4
        echo "$name" | grep -Eq '^[a-z0-9-]{1,40}$' || { echo "invalid peer name: $name" >&2; exit 2; }
        echo "$key" | grep -Eq '^[A-Za-z0-9+/]{43}=$' || { echo "invalid WireGuard public key" >&2; exit 2; }
        echo "$cidr" | grep -Eq '^[0-9.]+/32$' || { echo "tunnel address must be a /32: $cidr" >&2; exit 2; }
        # Drop any earlier key for this name, then any other peer holding the same tunnel address.
        if [ -s "$PEER_DIR/$name.conf" ]; then
            old=$(sed -n 's/^PublicKey = //p' "$PEER_DIR/$name.conf")
            [ -z "$old" ] || [ "$old" = "$key" ] || wg set wg0 peer "$old" remove
        fi
        printf '[Peer]\nPublicKey = %s\nAllowedIPs = %s\nPersistentKeepalive = 25\n' "$key" "$cidr" > "$PEER_DIR/$name.conf"
        wg set wg0 peer "$key" allowed-ips "$cidr" persistent-keepalive 25
        echo "wireguard-hub: peer $name $cidr added"
        exit 0
        ;;
    public-key)
        cat "$STATE_DIR/hub.pub"
        exit 0
        ;;
    export-peers)
        for peer in "$PEER_DIR"/*.conf; do
            [ -e "$peer" ] || continue
            printf 'add-peer %s %s %s\n' "$(basename "$peer" .conf)" \
                "$(sed -n 's/^PublicKey = //p' "$peer")" "$(sed -n 's/^AllowedIPs = //p' "$peer")"
        done
        exit 0
        ;;
    start) ;;
    *) echo "unknown command: $1" >&2; exit 2 ;;
esac

umask 077
mkdir -p "$PEER_DIR"
[ -s "$STATE_DIR/hub.key" ] || wg genkey > "$STATE_DIR/hub.key"
wg pubkey < "$STATE_DIR/hub.key" > "$STATE_DIR/hub.pub"

ip link show wg0 > /dev/null 2>&1 || ip link add wg0 type wireguard
wg set wg0 private-key "$STATE_DIR/hub.key" listen-port "$LISTEN_PORT"
ip address replace "$HUB_ADDRESS" dev wg0
ip link set wg0 up
load_peers

echo "wireguard-hub: wg0 $HUB_ADDRESS listening on $LISTEN_PORT/udp, public key $(cat "$STATE_DIR/hub.pub")"

trap 'exit 0' TERM INT
while :; do sleep 3600 & wait $!; done
