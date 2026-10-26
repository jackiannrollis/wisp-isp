# Project context · Corebalt ISP Platform

## What we are building

A multi-tenant SaaS for Kenyan ISPs, WISPs and Wi-Fi vendors: customers, packages, subscriptions, wallet billing, M-Pesa payments and router control in one platform, where **billing state is network entitlement**. Paid means online; expired past grace means a walled garden with a pay page; a payment restores access within seconds with no staff involved.

## Core flow (everything serves this)

`PaymentReceived` → `WalletCredited` → `SubscriptionRenewed` → `NetworkAccessChanged` → `SessionReauthorized` → `CustomerNotified`

## Stack

| Layer | Choice |
|---|---|
| Backend | Java 25, Spring Boot 4.x, Spring Modulith, Spring Security, Spring Data JPA, Flyway, MapStruct, Bean Validation, OpenAPI |
| Frontend | Angular 22, TypeScript, Signals, reactive forms, Angular Material or PrimeNG, ECharts |
| Data | PostgreSQL 17 (row-level security per tenant; `radacct` partitioned monthly), Redis (cache, rate limits) |
| Events | RabbitMQ cluster, quorum queues, transactional outbox, idempotent consumers |
| AAA | FreeRADIUS 3.2 with `rlm_sql` reading PostgreSQL views `radius_client` and `v_radius_auth`; two nodes (Hetzner and Nairobi) |
| Routers | MikroTik RouterOS v7: PPPoE (v1) and hotspot (v2); RADIUS CoA/Disconnect on UDP 3799; REST API and SNMP for monitoring; WireGuard tunnels to two hubs |
| Payments | M-Pesa Daraja: C2B Paybill confirmation and validation, STK Push, STK Query, Transaction Status |
| Messaging | Africa's Talking SMS (v1); WhatsApp and email (v2) |
| Infrastructure | Docker Compose, Caddy, Hetzner core; PostgreSQL replica, FreeRADIUS node B and WireGuard hub B in a Nairobi data centre; Prometheus, Grafana, Loki |

## Modules (Spring Modulith, one deployable)

`tenancy` · `identity` · `customers` · `packages` · `subscriptions` · `billing` · `payments` · `network` · `notifications` · `reporting` · `hotspot` (v2) · `support` (v2)

Modules talk through published events or small explicit interfaces, never through another module's entities or repositories. Each module owns its tables, prefixed with the module name. See `docs/02-architecture.md` for what each module owns, publishes and consumes.

## Key design rules

1. **RADIUS reads the database directly.** Logins never depend on the API. The date logic lives in the `v_radius_auth` view and in `subscriptions.AccessPolicy`; a test asserts they agree.
2. **Expiry is enforced at login time plus `Session-Timeout`** (seconds to the next profile change, max 24 h). CoA/Disconnect handles mid-session changes. Scheduled jobs only send reminders and compute reports.
3. **Expired users are restricted, not rejected**: `restricted` pool and address list, pay page reachable. `Access-Reject` only for `PENDING`, `SUSPENDED`, `TERMINATED`.
4. **Money is an append-only ledger** on a per-customer wallet; payments credit, renewals debit, corrections are new entries. Integer KES cents, never floats.
5. **M-Pesa callbacks are idempotent** on (`tenant_id`, `TransID`); unmatched payments go to a suspense queue; reconciliation runs daily.
6. **Everything is tenant-scoped**: `tenant_id` on every table, RLS in PostgreSQL, tenant context mandatory in code, customer numbers and generated PPPoE usernames prefixed with the tenant code (`CB001238`; a username kept from an ISP's old system by the cutover import is unique within its tenant, ADR 0014).
7. **Secrets are write-only and envelope-encrypted**; never logged, never returned by the API.

## Stored states and derived access

Stored `SubscriptionState`: `PENDING`, `ACTIVE`, `PAUSED`, `SUSPENDED`, `TERMINATED`.
Derived `AccessProfile`: `FULL`, `THROTTLED`, `RESTRICTED`, `REJECT`, computed from `state`, `expires_at`, `grace_days`, tenant `grace_mode` and the current time.
Derived `DisplayStatus` adds `EXPIRING`, `GRACE`, `RESTRICTED` for screens and SMS.

## Current phase

v1 (sellable core): PPPoE billing with M-Pesa, automatic restrict and restore, SMS, admin dashboard, basic portal, roles and audit. Hotspot, vouchers, support, monitoring and WhatsApp are v2; installations, inventory, agents, accounting and eTIMS are v3. Gate 1 is one pilot ISP through two billing cycles with no manual reconnects. See `docs/09-roadmap.md`.

## Where to read more

`docs/README.md` indexes the long-form documents; `docs/adr/` holds the decision records; this folder's `domain-rules.md`, `conventions.md` and `glossary.md` are the working references.
