# ADR 0011 · A tunnel worker, the only process that talks to routers

**Status:** Accepted
**Date:** 2026-10-20

## Context

Routers are reached only through WireGuard (N6, ADR 0006): RouterOS REST and SNMP for monitoring, and RADIUS CoA/Disconnect on UDP 3799 (N5). The API runs on the core network and should not hold router management credentials or depend on a tunnel route. On a production host the hub is WireGuard on the host (wg-quick); in local development it is the `wireguard` container, whose network namespace FreeRADIUS already shares. Monitoring must say whether a router is up; FreeRADIUS accounting alone cannot, because a router with no active sessions sends none.

## Decision

- **One image, two roles.** The backend image runs either as the API (default) or as the tunnel worker (Spring profile `worker`, `isp.worker.enabled=true`). The worker code is identical on every host; only its network placement differs.
- **What the worker does.**
  - It polls every monitored router's RouterOS REST `GET /rest/system/resource` every 60 s on its own executor.
  - It is the only consumer of `network.router-poll`, the queue for `RouterPollRequested` events, which the API publishes when staff ask for a poll now.
  - CoA/Disconnect will be its second job.
  - It records status through the outbox: three misses in a row publish `RouterOffline`, the next answer publishes `RouterOnline`. The API's relay delivers them.
- **What the worker does not do.**
  - It does not run migrations, the API's scheduled jobs, the outbox relay, other listeners or bootstrap.
  - It does not hold a token signing key and accepts no tokens.
  - Its HTTP port answers only `/actuator/health`; everything else is a 404, and no port is published.
- **Database role `isp_worker`.** It is not `isp_app` and holds only these grants, which `DatabaseGrantsIT` pins exactly:
  - SELECT on `tenancy_tenant` (`id`, `status`) and on `tenancy_tenant_data_key`, to iterate tenants and unwrap the data key.
  - SELECT on the `network_router` columns it polls with, and UPDATE on its status columns.
  - INSERT on `outbox_event` and on `shared_processed_event`.

  For CoA it will add SELECT on `radius_client_v3` and the subscription columns it needs. It reads the router secret when it sends, never caches it, so a rotated secret takes effect on the next request. That slice amends ADR 0009 to name `isp_worker` as the second reader; a future Go service would use the same role.
- **Credentials.**
  - The router's REST login is stored envelope-encrypted (T4) by the API, which never returns it; the SecretStore purpose includes the router ID.
  - Only the worker decrypts it, for the length of one call, and never logs it.
  - The router side is the onboarding script's read-only `corebalt-api` user, limited to the hub address, with the www service listening on the tunnel only.
- **Placement.** One worker replica per hub (`ISP_WORKER_HUB`).
  - **Local development:** the worker shares the `wireguard` container's namespace (`network_mode: service:wireguard`), like FreeRADIUS, so it reaches routers by tunnel address and its packets leave from the hub address.
  - **Hetzner and Nairobi hosts:** WireGuard runs on the host (wg-quick). FreeRADIUS binds 1812/1813 on the hub tunnel IP. The worker runs with host networking, or on a Docker network whose masquerade makes its traffic leave from the hub IP, so CoA comes from the address routers accept.
  - `infra/README.md` documents both layouts.

## Consequences

- The API has no route to routers and no router credential in memory. A compromised API can ask for a poll, not reach a router.
- A second hub is a second worker with the same image. Status per hub is not modelled yet: with hub B (ADR 0008) each router gets a status per hub, and it counts as online if either answers.
- `RouterPollRequested` published while no worker is running is dropped (no queue is bound); the 60-second poll covers it.
- The worker needs its own login (`ISP_DB_WORKER_PASSWORD`). Volumes created before step 8 need it created once (infra/README.md).
- Accounting stays the secondary "last heard" signal (`last_accounting_at`, from the API's accounting job), never the online signal.
