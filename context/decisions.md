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
| 2026-10-19 | Local builds accept JDK 25 or newer (enforcer `[25,)`); bytecode stays `--release 25`; CI and the Docker image keep building and testing on JDK 25 | Developers may run newer JDKs (27 locally); Java 25 LTS remains the production runtime (ADR 0001) | backend/pom.xml, CLAUDE.md |
| 2026-10-19 | FreeRADIUS matches the router by the packet source address (`Packet-Src-IP-Address` = tunnel IP), not `NAS-IP-Address`; every NAS, including lab radtest, comes from `radius_client_v*` | The source address is what WireGuard authenticates; NAS-IP-Address is asserted by the client (T3) | radius/raddb/mods-available/sql |
| 2026-10-19 | FreeRADIUS runs in the WireGuard hub's network namespace (compose `network_mode: service:wireguard`) | Routers are seen by tunnel address and CoA/Disconnect leave from the hub address the router trusts (N5, N6) | infra/docker-compose.yml |
| 2026-10-19 | The local WireGuard hub container runs as root with only `NET_ADMIN`, read-only root filesystem and no-new-privileges; path-scoped Trivy exception | Docker Desktop ignores file capabilities, so an unprivileged user cannot create `wg0` | infra/wireguard/Dockerfile, .trivyignore.yaml |
| 2026-10-19 | Step-2 lab tables (`network_lab_router`, `network_lab_line`) live in a versioned migration behind `v_radius_auth_v2`; step 3 publishes v3 over real tables and drops them | FreeRADIUS needs real views to test against before customers and subscriptions exist | V202610191000 migration |
| 2026-10-19 | PPPoE passwords and router RADIUS secrets live in `network_radius_credential`, readable only through the RADIUS views (app role: insert and update `secret`, select key columns only); platform-generated, shown once, then regenerate-only; T4 exception; encrypted backups and disk encryption required | FreeRADIUS must compare them in plain form and cannot decrypt app-side envelope encryption (ADR 0004) | [ADR 0009](../docs/adr/0009-radius-credentials-readable-only-by-radius.md) |
| 2026-10-19 | The line belongs to `subscriptions` (package, router, PPPoE username); `customers` holds only profile, contact and location; `network` owns the credential table | One owner per fact; the username is needed for display, SMS and radacct matching | docs/02, docs/03 |
| 2026-10-19 | When module A needs B but B already depends on A's events, A declares a port in its `api` package and B implements it (first case: `LineCredentialStore`, `RouterDirectory`) | Spring Modulith forbids cycles; network consumes subscription events | context/conventions.md |
| 2026-10-19 | Issuing and regenerating PPPoE passwords and router secrets take no `Idempotency-Key` | A replay would have to return the secret again; a retried regeneration just issues another value. Revisit when the idempotency store arrives with money writes (step 5) | LineService, RouterService |
| 2026-10-19 | Step 3 opens lines as `PENDING` with `grace_days` 0; the CSV import creates customers and PENDING lines but carries no state or expiry | Activation, renewal and the copy of grace days (S6) are the step-4 lifecycle; states are never set outside it | subscriptions module |
| 2026-10-19 | Phone numbers are stored in E.164; national Kenyan forms (`07…`, `01…`, `7…`, `254…`) are read as +254 | Tenants are Kenyan ISPs; M-Pesa and SMS use international form | customers.domain.PhoneNumbers |
| 2026-10-19 | `THROTTLED` on a package without throttle speeds keeps the package speed | docs/03: null throttle columns mean unlimited | v_radius_auth_v3 |

## How to add a row

Add the row when the decision is made, not when the code lands. If it changes an earlier row, mark the old one "Superseded by …" rather than deleting it.
