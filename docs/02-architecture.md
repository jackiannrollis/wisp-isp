# 02 · Architecture and tech stack

## Overview

One Spring Boot modular monolith owns all business logic. PostgreSQL is the single source of truth. FreeRADIUS reads subscriber entitlements straight from PostgreSQL, so logins never depend on the API being up. RabbitMQ carries events between modules and drives background work. Every tenant router reaches the platform over WireGuard tunnels.

```
   Angular web apps             M-Pesa Daraja                SMS gateway
   (admin, portal)           (callbacks, STK Push)        (Africa's Talking)
          │ HTTPS                     │                           ▲
          ▼                           ▼                           │
 ┌─────────────────────────────────────────────────────────────────────────┐
 │  Spring Boot API · modular monolith · Hetzner                           │
 │  tenancy · identity · customers · packages · subscriptions · billing    │
 │  payments · network · notifications · reporting                         │
 │  ─────────────── RabbitMQ · events and background work ─────────────── │
 └──────────┬──────────────────────────────┬───────────────────────────────┘
            │ CoA, RouterOS REST           │ SQL
            │ (over WireGuard)             ▼
            │                 PostgreSQL primary ───replication──▶ PostgreSQL replica
            │                    (Hetzner)                           (Nairobi DC)
            │                        │ entitlements                       │
            │                        ▼                                    ▼
            │                 FreeRADIUS node A                    FreeRADIUS node B
            │                        ▲ login, accounting                  ▲
            ▼                        │                                    │
 ┌─────────────────────────────────────────────────────────────────────────┐
 │  Tenant MikroTik routers · WireGuard peers to both hubs                 │
 └─────────────────────────────────────────────────────────────────────────┘
                                     ▲
                   Subscribers: home routers on PPPoE, phones on hotspot
```

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Core backend | Java 25 LTS, Spring Boot 4.x, one modular monolith with Spring Modulith module boundaries | Billing, ledgers, transactions and RBAC are domain-heavy work where Spring is strongest; one deployable is far easier to run than microservices |
| Admin dashboard and customer portal | Angular 22, TypeScript, Angular Material or PrimeNG, Signals, ECharts | Opinionated structure keeps a large admin app consistent as developers join; reactive forms and route guards suit permission-heavy screens |
| Public marketing site | Angular with server-side rendering, or any static site generator; kept separate from the app | Server rendering and SEO only matter on public pages |
| Hotspot captive portal (v2) | Plain HTML and a little JavaScript, no framework | Must load instantly on cheap phones and live inside the walled garden |
| Database | PostgreSQL 17, RADIUS accounting tables partitioned by month | The ledger needs ACID transactions; accounting grows fastest of all tables |
| Events and jobs | RabbitMQ (two-node cluster, quorum queues), fed by a transactional outbox; Redis for cache and rate limits | Payment processing, CoA pushes, usage roll-ups and SMS run as independent workers with retries; a broker outage delays restores and SMS but never logins |
| AAA | FreeRADIUS 3.2 with `rlm_sql` on PostgreSQL, two nodes | Logins keep working when the API is down |
| Router control | RouterOS v7 REST API and RADIUS CoA/Disconnect (UDP 3799), all over WireGuard | Reaches routers behind NAT and dynamic IPs; enables instant reconnect |
| Router monitoring | SNMP and RouterOS REST polling into Prometheus | CPU, RAM, uptime, traffic and online users per router |
| Payments | M-Pesa Daraja: C2B Paybill, STK Push, Transaction Status; cards, bank and Trilho later | Paybill with the customer number as account reference identifies the payer automatically |
| Messaging | Africa's Talking SMS; WhatsApp and email in v2 | SMS reaches every subscriber, including restricted ones |
| Observability | Prometheus, Grafana, Loki; structured JSON logs with tenant and correlation IDs | One place to see routers, RADIUS, queues and the API |
| Infrastructure | Docker Compose on Hetzner for the core; one FreeRADIUS node, a PostgreSQL replica and a WireGuard hub in a Nairobi data centre; Caddy for TLS | Cheap compute for the core; in-country logins survive an international link outage |
| Later, only if needed | A Go service for telemetry and accounting ingest, consuming the same RabbitMQ events | When router polling or accounting volume outgrows the monolith |

## Modules

The monolith is one deployable, but its modules are strict. Spring Modulith verifies in a test that no module reaches into another module's internals.

| Module | Owns | Publishes | Consumes |
|---|---|---|---|
| `tenancy` | Tenants, settings, branding, encrypted secrets | `TenantCreated` | — |
| `identity` | Staff users, roles, permissions, 2FA, audit log, portal logins | `AuditRecorded` | every state-changing command |
| `customers` | Customer profile, contact details, location | `CustomerCreated` | — |
| `packages` | Package definitions and prices | `PackageChanged` | — |
| `subscriptions` | Subscription (the line: package, router, PPPoE username, MAC, static IP), expiry, lifecycle states, renewals, proration, pause | `SubscriptionRenewed`, `SubscriptionStateChanged`, `SubscriptionReminderDue` | `WalletCredited` |
| `billing` | Wallet, ledger, receipts, invoices, arrears, credit notes | `WalletCredited`, `WalletDebited`, `ReceiptIssued` | `PaymentReceived`, `SubscriptionRenewed` |
| `payments` | M-Pesa Daraja, STK Push, suspense queue, reconciliation, manual payments | `PaymentReceived`, `PaymentUnmatched` | — |
| `network` | Routers, sites, IP pools, RADIUS credentials (PPPoE passwords, router secrets; ADR 0009), RADIUS entitlement rows, CoA, RouterOS REST, sessions, accounting ingest, usage roll-ups, fair-use | `NetworkAccessChanged`, `SessionReauthorized`, `RouterOffline`, `UsageThresholdReached` | `SubscriptionStateChanged`, `SubscriptionRenewed` |
| `notifications` | Templates, SMS dispatch (WhatsApp and email later), delivery log | `CustomerNotified` | most events above |
| `hotspot` (v2) | Hotspot plans, captive portal API, vouchers, hotspot sessions | `VoucherRedeemed` | `PaymentReceived` |
| `support` (v2) | Tickets, outages, technician assignment | `TicketUpdated` | `RouterOffline` |
| `reporting` | Dashboard counts, revenue and usage reports, read from the replica | — | — |

Rules:

- Modules communicate through published events or a small explicit API interface, never through another module's repositories or JPA entities.
- Each module owns its tables; table names carry the module prefix (`billing_ledger_entry`, `network_router`).
- The `payments` module knows nothing about subscriptions; it only says "money arrived for this customer". The `billing` module turns that into a wallet credit; `subscriptions` decides whether a renewal is due.

## Events: outbox to RabbitMQ

1. A command handler writes its business change **and** an `outbox_event` row in the same database transaction.
2. A relay publishes outbox rows to RabbitMQ and marks them sent. If the broker is down, rows wait; nothing is lost.
3. Consumers are idempotent: every event carries a UUID, and each consumer records processed IDs.
4. Queues are durable quorum queues; messages are persistent. Dead-letter queues collect failures after three retries with backoff, and an alert fires on a non-empty dead-letter queue.
5. The same events feed the audit trail and, later, any Go service.

Naming: events are past tense, `<Aggregate><Verb>` (`PaymentReceived`), carry IDs rather than whole entities, and are versioned (`v1` in the routing key).

## Router connectivity

- During onboarding, the platform generates a RouterOS script for each router. It creates a WireGuard interface with two peers: the Hetzner hub and the Nairobi hub.
- Each FreeRADIUS node identifies the router by its tunnel IP on that node's hub, with a per-router shared secret.
- CoA and Disconnect requests, the RouterOS REST API and SNMP polling all travel through the tunnels. No router port is exposed to the public internet.
- The hub records the last WireGuard handshake per peer; a stale handshake is the first sign a router is offline.

## Infrastructure topology

| Location | Runs |
|---|---|
| Hetzner (core) | Two API instances behind Caddy, PostgreSQL primary, RabbitMQ cluster, Redis, FreeRADIUS node A, WireGuard hub A, Prometheus, Grafana, Loki |
| Nairobi data centre | PostgreSQL streaming replica, FreeRADIUS node B (reads the replica), WireGuard hub B |
| Tenant sites | MikroTik routers with WireGuard peers to both hubs |

Write traffic (payments, renewals, staff actions) needs the Hetzner core. Logins and accounting need only the nearest RADIUS node and its database.

## Environments

| Environment | Purpose | Notes |
|---|---|---|
| Local | Development | Docker Compose: PostgreSQL, RabbitMQ, Redis, FreeRADIUS, a MikroTik CHR virtual router for PPPoE tests, Daraja sandbox |
| Staging | Integration and tenant demos | Mirrors production on smaller machines; Daraja sandbox; real SMS to a test number only |
| Production | Tenants | Hetzner core plus the Nairobi node |

Deployments are Docker images tagged by git SHA, rolled one API instance at a time. Flyway migrations run before the new version starts and must be backwards compatible with the previous version for one release.
