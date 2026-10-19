# Infrastructure

Local stack, network layouts and operations. Commands for daily use are in `CLAUDE.md`; the network
lab runbook is `infra/lab/README.md`.

## Services

| Service | What it is | Reaches routers? |
|---|---|---|
| `postgres` | PostgreSQL 17, roles `isp_owner`, `isp_app`, `isp_radius`, `isp_worker` | no |
| `rabbitmq`, `redis` | events; token versions and sign-in limits | no |
| `backend` | the API (profile `local`); runs migrations and every scheduled job | **never** |
| `wireguard` | hub A: the WireGuard endpoint routers peer with | is the tunnel |
| `freeradius` | RADIUS auth and accounting; reads two views, writes `radacct` | receives from routers |
| `tunnel-worker` | the backend image in profile `worker` (ADR 0011): router polls, CoA and Disconnect | **only this one** |
| `caddy` | reverse proxy for the API and dev servers; lab pay page | no |

## Network layouts

The worker and FreeRADIUS code is identical in both layouts; only where they sit changes.

### Local development (this compose file)

```
 router ──WireGuard──▶ [ wireguard container: wg0 10.200.0.1 ]
                          │ shared network namespace
                          ├── freeradius     binds :1812/:1813 in that namespace
                          └── tunnel-worker  polls http://<tunnel ip>/rest, health on :8090
 backend, postgres, rabbitmq, redis on the compose network (no route to 10.200.0.0/24)
```

- FreeRADIUS and the worker use `network_mode: service:wireguard`, so packets from routers arrive with their tunnel address and replies, polls and CoA leave from the hub address `10.200.0.1`.
- The `wireguard` container publishes 51820/udp, and 1812/1813/udp for FreeRADIUS. The worker publishes nothing.
- Sharing the hub container's namespace is for local development only.

### Hetzner (hub A) and Nairobi (hub B) hosts

```
 router ──WireGuard──▶ [ host: wg-quick wg0, hub IP 10.200.0.1 (A) / 10.200.1.1 (B) ]
                          ├── freeradius     binds 1812/1813 on the hub tunnel IP
                          └── tunnel-worker  host networking (or a bridge with masquerade)
```

- WireGuard runs on the host with `wg-quick@wg0`; peers are added by the onboarding tooling.
- FreeRADIUS binds 1812/1813 on the hub tunnel IP only. The firewall allows them only on `wg0`, so nothing listens publicly (N6).
- The worker runs with host networking. Alternatively it runs on a Docker bridge network: Docker's masquerade then makes its polls and CoA leave from the host, and a routing rule must send `10.200.0.0/16` through `wg0` so they leave from the hub IP. Routers accept REST only from the hub address (the onboarding script's `corebalt-api` user) and CoA only from their RADIUS server.
- One worker replica per hub, `ISP_WORKER_HUB=A` or `B`, each with the hub's own database login (`isp_worker`).

## The worker's database login

`infra/postgres/init/01-roles.sh` creates `isp_worker` with `ISP_DB_WORKER_PASSWORD` on a new volume. Migrations run as `isp_owner`, which may not create roles, so on a volume created before step 8 the migration `V202610201601` fails ("permission denied to create role") until the superuser creates the role, once, before migrating:

```
docker compose exec postgres psql -U postgres -d isp \
  -c "CREATE ROLE isp_worker LOGIN PASSWORD '<ISP_DB_WORKER_PASSWORD from .env>'" \
  -c "GRANT CONNECT ON DATABASE isp TO isp_worker"
```

Type the password at a prompt rather than leaving it in shell history where that matters. Staging and production create every role (`isp_app`, `isp_radius`, `isp_worker`) before the first migration.

## Accounting upkeep (API)

The API's accounting job creates `radacct` partitions three months ahead, drops months older than 13, closes sessions silent for 15 minutes as `Stale` and rolls usage into `network_usage_daily` every 15 minutes (docs/06 Accounting).
