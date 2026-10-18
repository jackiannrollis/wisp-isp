# ADR 0008 · Two RADIUS nodes, one in Nairobi, before a second tenant

**Status:** Accepted
**Date:** 2026-10-17

## Context

On a shared platform, a RADIUS outage stops new logins for every subscriber of every tenant ISP at once. The core runs at Hetzner for cost; an international link problem between Kenya and Europe would otherwise take all Kenyan logins down.

## Decision

Run two FreeRADIUS nodes: node A at Hetzner reading the PostgreSQL primary, node B in a Nairobi data centre reading a streaming replica, with every router configured to use both. This topology, a documented replica-promotion procedure and a quarterly failover drill are in place before the second tenant is onboarded.

## Consequences

- Logins and accounting survive an API outage, a Hetzner outage and an international link outage.
- Writes (payments, renewals, staff actions) still need the Hetzner core; that is an accepted degradation because existing sessions stay up.
- A second hosting location adds cost and operations work from the first paying tenant onward.
- Replication lag is a monitored metric: node B may see a renewal a few seconds late, which is acceptable because the CoA for a restore is sent by the core.
