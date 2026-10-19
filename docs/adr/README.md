# Architecture decision records

Each record states the context, the decision and its consequences at the time it was made. A superseded record stays in place with its status changed and a link to its successor.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith-spring-boot.md) | One Spring Boot modular monolith, not microservices | Accepted |
| [0002](0002-angular-for-web-apps.md) | Angular for the admin dashboard and customer portal | Accepted |
| [0003](0003-rabbitmq-with-transactional-outbox.md) | RabbitMQ for events and background work, fed by a transactional outbox | Accepted |
| [0004](0004-freeradius-reads-postgresql-directly.md) | FreeRADIUS reads entitlements from PostgreSQL views, not from the API | Accepted |
| [0005](0005-wallet-ledger-not-invoice-matching.md) | A wallet ledger instead of matching payments to invoices | Accepted |
| [0006](0006-wireguard-to-every-router.md) | WireGuard tunnels to every tenant router | Accepted |
| [0007](0007-restricted-profile-not-reject.md) | Expired subscribers get a restricted profile, not an Access-Reject | Accepted |
| [0008](0008-two-radius-nodes-one-in-nairobi.md) | Two RADIUS nodes, one in Nairobi, before a second tenant | Accepted |
| [0009](0009-radius-credentials-readable-only-by-radius.md) | RADIUS credentials are stored readable only by FreeRADIUS, not envelope-encrypted | Accepted |
| [0010](0010-self-issued-jwt-and-sign-in.md) | Self-issued RS256 tokens, rotating refresh tokens and per-request revocation | Accepted |
| [0011](0011-tunnel-worker.md) | A tunnel worker, the only process that talks to routers | Accepted |

## Writing a new record

Copy the structure of any record: **Status**, **Date**, **Context**, **Decision**, **Consequences**. Number it with the next free number, add it to this table and to `context/decisions.md`.
