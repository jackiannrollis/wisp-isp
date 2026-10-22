# syntax=docker/dockerfile:1
# The web front of staging and production (docs/02, decisions.md 2026-10-21): Caddy with the ACME
# DNS-01 plugins for the wildcard certificate, serving the admin and portal builds and proxying the
# API. Built from the repository root: docker build -f infra/web/Dockerfile .
FROM node:24.16.0-alpine3.22 AS apps
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2.11.7-builder AS caddy
# Both DNS providers are built in; ACME_DNS_PROVIDER picks one per environment.
RUN xcaddy build v2.11.7 \
    --with github.com/caddy-dns/cloudflare@v0.2.4 \
    --with github.com/caddy-dns/hetzner/v2@v2.0.1

FROM caddy:2.11.7
COPY --from=caddy /usr/bin/caddy /usr/bin/caddy
COPY infra/web/Caddyfile /etc/caddy/Caddyfile
COPY --from=apps /src/dist/admin/browser /srv/admin
COPY --from=apps /src/dist/portal/browser /srv/portal
# Ports 80 and 443 without root: the binary keeps only the bind capability.
RUN apk add --no-cache libcap \
    && setcap cap_net_bind_service=+ep /usr/bin/caddy \
    && apk del libcap \
    && addgroup -S -g 1000 caddy && adduser -S -u 1000 -G caddy caddy \
    && mkdir -p /data/caddy /config/caddy && chown -R caddy:caddy /data /config
USER caddy
