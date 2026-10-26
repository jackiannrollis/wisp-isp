# ADR 0015 · Router retirement: the platform revokes, the host's sync removes the peer

**Status:** Accepted
**Date:** 2026-10-26

## Context

Routers had no way to leave the platform. A router replaced after a failure, stolen, or taken out of service kept a valid RADIUS secret in `radius_client_v3` and stayed a WireGuard peer of the hub: whoever held the device, with the private key the onboarding script put on it, could still reach FreeRADIUS as that ISP's router. The host's router sync (ADR 0013) added peers from the database but never removed one, and the pilot-onboarding runbook told the operator to remove a peer by hand. A replacement could not take the old router's name either, because names are unique per tenant.

What exists: `radius_client_v3` lists only routers with a secret; the tunnel worker polls only routers with a REST login (ADR 0011); CoA and Disconnect find sessions in `radacct` and the router's address and secret in `radius_client_v3`; logins work through any router of the tenant, so a line's router is bookkeeping (T3).

## Decision

Retirement, final, in one API transaction (`POST /api/v1/routers/{id}/retire`, `network.write`, a reason, an `Idempotency-Key`; rule N10):

- The router's lines that are not terminated move to a replacement router of the same tenant, which is required while there are any; terminated lines stay on it as history. The move goes through `subscriptions.api.LineRouters`: network already depends on the subscriptions API.
- Its RADIUS secret is set to null (the row stays), so `radius_client_v3` drops it; its REST login is cleared, so the worker stops polling it; its open `radacct` sessions are closed at their last update with cause `Router-Retired`, so no CoA or Disconnect is ever aimed at it.
- An audit row with the reason, the replacement and the counts (A1), and `RouterRetired`, on which notifications clears the router's open offline alert.
- The row keeps `retired_at`, `retired_reason` and `replaced_by_router_id`. Every action on a retired router is refused (409); the list shows active routers unless asked for retired ones; the dashboard counts active routers only.
- The name may be reused by the replacement: the per-tenant unique index on the name covers active routers only. The tunnel address is never reused: the RADIUS view lists a tenant's routers by address, so a second router on the same address would answer twice, and lifting that would need a new view version (S3).

On the host, the router sync now also **removes**: a retired router's peer is taken off `wg0` with the new `corebalt-wg-hub remove-peer <name>` (its file deleted; removing an absent peer succeeds), and the secret's change makes the sync restart FreeRADIUS as it already does for a new secret. Both happen within a minute of the retirement, with nothing for the operator to do. A peer file that no router accounts for (added by hand) is still only reported. This reverses ADR 0013's "never removed" for retired routers only.

A poll already in flight when a router is retired, or its REST login removed, now changes nothing: the worker's status updates apply only while the router still has a REST login, and its online and offline events follow only from an update that applied.

## Consequences

- A stolen or replaced router loses its way in within a minute of the retirement. Until the sync runs, FreeRADIUS still holds the old client list, but the worker no longer polls it and no CoA targets it.
- Retirement is final: a router that comes back is registered again, as a new router with a new address and a new onboarding script.
- The tunnel address space shrinks by one per retirement; the hub's /24 (or larger) is ample for an ISP's routers, and the runbook says to pick a new address for a replacement.
- The stack test runs the real `routersync` against its database with a stand-in hub (add, then remove after a retirement, then nothing), and the converge test runs `remove-peer` on the real hub; neither proves the RouterOS side, which the network lab will.
