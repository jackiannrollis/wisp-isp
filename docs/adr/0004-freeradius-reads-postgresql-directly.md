# ADR 0004 · FreeRADIUS reads entitlements from PostgreSQL views, not from the API

**Status:** Accepted
**Date:** 2026-10-17

## Context

Every PPPoE login on every tenant's routers passes through RADIUS. If RADIUS depended on the API, an API outage or deployment would stop logins for every ISP at once. The alternative, having the application push users into router configuration directly, does not scale past a few hundred customers and breaks when the application cannot reach a router.

## Decision

FreeRADIUS 3.2 with `rlm_sql` reads two PostgreSQL views: `radius_client` (routers by tunnel IP and secret) and `v_radius_auth`, which computes at query time the access profile and reply attributes from subscription state, expiry, grace days and tenant policy. The same date logic exists in the `subscriptions` module as `AccessPolicy`, and a test asserts both agree. Accounting is written to `radacct` by FreeRADIUS. The application touches routers only to push CoA and Disconnect for mid-session changes.

## Consequences

- Logins keep working when the API is down or deploying; node B in Nairobi also keeps working from the replica when the Hetzner core is unreachable.
- Expiry is enforced at login time by the database, never only by a scheduled job.
- The view is a contract: changing the date logic means changing the view, the `AccessPolicy` class and the test together.
- FreeRADIUS needs read access to decrypted PPPoE passwords; this is granted through a restricted database function, and the RADIUS database role has no other access.
- `Session-Timeout` capped at 24 hours bounds the effect of any missed CoA.
