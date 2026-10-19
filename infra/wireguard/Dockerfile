# WireGuard hub for the local stack (ADR 0006, N6): routers peer with it and reach RADIUS (and later
# receive CoA, REST and SNMP) only through the tunnel. FreeRADIUS shares this container's network
# namespace, so it sees each router by its tunnel address, which is what radius_client matches.
#
# Runs as root on purpose: creating wg0 needs CAP_NET_ADMIN, and file capabilities on ip/wg are not
# honoured by every Docker runtime (Docker Desktop ignored them). Compose confines it instead: all
# capabilities dropped except NET_ADMIN, read-only root filesystem, no-new-privileges. The hub has
# no listening service besides the kernel's WireGuard socket. Recorded in .trivyignore.yaml.
FROM alpine:3.22

RUN apk add --no-cache iproute2 wireguard-tools-wg \
    && mkdir -p /var/lib/wireguard

COPY --chmod=0755 entrypoint.sh /usr/local/bin/wireguard-hub

EXPOSE 51820/udp
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD ["wg", "show", "wg0", "listen-port"]
ENTRYPOINT ["/usr/local/bin/wireguard-hub"]
