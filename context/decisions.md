# Decision log

One row per decision that shapes the code. The ADR column links to the full record where one exists; rows without an ADR are settled product choices from the planning documents.

| Date | Decision | Why in one line | Record |
|---|---|---|---|
| 2026-10-17 | Billing state is network entitlement: paid → online, expired → restricted, payment → instant restore | The automation is the product, not a feature | docs/01 |
| 2026-10-17 | Spring Boot modular monolith on Java 25; Go deferred to a possible telemetry service | Domain-heavy system; one deployable is easier to run | [ADR 0001](../docs/adr/0001-modular-monolith-spring-boot.md) |
| 2026-10-17 | Angular 22 for admin dashboard and customer portal | Opinionated structure for a large internal app; no SSR needed behind login | [ADR 0002](../docs/adr/0002-angular-for-web-apps.md) |
| 2026-10-17 | RabbitMQ from day one, with a transactional outbox; Redis for cache only | Independent, retryable workers for the event chain; nothing lost between commit and broker | [ADR 0003](../docs/adr/0003-rabbitmq-with-transactional-outbox.md) |
| 2026-10-17 | FreeRADIUS reads `radius_client` and `v_radius_auth` views from PostgreSQL directly | Logins never depend on the API | [ADR 0004](../docs/adr/0004-freeradius-reads-postgresql-directly.md) |
| 2026-10-17 | Wallet ledger instead of invoice matching | Partial, early and over-payments need no special rules | [ADR 0005](../docs/adr/0005-wallet-ledger-not-invoice-matching.md) |
| 2026-10-17 | WireGuard tunnels from every router to two hubs | NAT and dynamic IPs; CoA always has a route | [ADR 0006](../docs/adr/0006-wireguard-to-every-router.md) |
| 2026-10-17 | Expired users get a `RESTRICTED` profile, not `Access-Reject` | A pay page beats a dead connection | [ADR 0007](../docs/adr/0007-restricted-profile-not-reject.md) |
| 2026-10-17 | Two RADIUS nodes, one in Nairobi, before the second tenant | Corebalt's uptime is every tenant's uptime | [ADR 0008](../docs/adr/0008-two-radius-nodes-one-in-nairobi.md) |
| 2026-10-17 | Expiry enforced at login plus `Session-Timeout` capped at 24 h; jobs only remind and report | A stuck scheduler cannot affect access | docs/04 |
| 2026-10-17 | Store only `PENDING`, `ACTIVE`, `PAUSED`, `SUSPENDED`, `TERMINATED`; derive the rest from dates | No state can drift from the dates that define it | docs/04 |
| 2026-10-17 | M-Pesa callbacks idempotent on `TransID`; suspense queue; daily statement reconciliation | Mistyped references and missed callbacks are routine | docs/05 |
| 2026-10-17 | Customer numbers and PPPoE usernames prefixed with the tenant code | Uniqueness on shared RADIUS servers | docs/07 |
| 2026-10-17 | PostgreSQL row-level security plus a mandatory tenant context | Two layers of isolation | docs/07 |
| 2026-10-17 | PPPoE first; hotspot and vouchers in v2; field operations in v3 | A sellable v1 for one pilot ISP before anything else | docs/09 |
| 2026-10-17 | Hotspot captive portal is plain HTML, not Angular | Must load inside the walled garden on cheap phones | docs/02 |
| 2026-10-17 | Core on Hetzner, with a PostgreSQL replica, RADIUS node B and WireGuard hub B in a Nairobi data centre | Cost for the core; in-country logins survive link outages | docs/08 |
| 2026-10-17 | Money as integer KES minor units; UTC timestamps; UUIDv7 IDs | Exact arithmetic, no time-zone bugs, time-ordered keys | context/conventions.md |
| 2026-10-18 | `outbox_event` is transport, not append-only: app role gets INSERT and column-level UPDATE on `published_at`, `attempts`, `last_error`; no DELETE; owner job purges published rows after 7 days | The relay must mark rows sent (docs/02); ledger and audit log stay append-only | context/conventions.md |

## How to add a row

Add the row when the decision is made, not when the code lands. If it changes an earlier row, mark the old one "Superseded by …" rather than deleting it.
