# 08 · Reliability, security and compliance

A Corebalt outage takes every tenant's customers offline, so reliability is part of the product, not background operations work.

## Failure modes

| Failure | Effect on subscribers | Effect on business | Design response |
|---|---|---|---|
| API down | None: logins and accounting go to RADIUS and the database | Portal, dashboard and M-Pesa callbacks stop until it returns | Two API instances; callbacks are reconciled daily so missed ones are recovered |
| RabbitMQ down | None for logins | Restores, SMS and roll-ups are delayed | Two-node cluster with quorum queues; the outbox holds events until the broker returns |
| PostgreSQL primary down | Node A fails over to node B, which reads the replica; existing sessions continue | All writes stop | Documented promotion of the replica; nightly restore drills; alert on replication lag |
| Hetzner unreachable from Kenya | Logins via node B and the replica continue | Payments and portal unreachable until the link returns | In-country RADIUS and replica are the reason the Nairobi node exists |
| Nairobi node down | Node A serves | None | Routers list both nodes |
| Both RADIUS nodes down | Existing PPPoE sessions stay up; only new logins fail | Growing complaints as sessions time out | 24-hour `Session-Timeout` cap bounds the damage; this is the one scenario drills must cover |
| Router tunnel down | Sessions continue; CoA fails | Restores delayed on that router | CoA retries; `Session-Timeout` self-heals within a day; staff alerted |
| M-Pesa callbacks missing | None | Payments not reflected; support load | Callback-gap alert, STK query, daily reconciliation, suspense queue |

## Reliability practices

- Two FreeRADIUS nodes, one in a Nairobi data centre, every router configured with both.
- PostgreSQL primary with a streaming replica in Nairobi; each RADIUS node reads the nearest database; replication lag alerted above 30 seconds.
- RabbitMQ cluster with durable quorum queues and persistent messages; dead-letter queues alerted.
- Nightly full backups plus continuous WAL archiving for point-in-time recovery, encrypted at rest and in transit to storage; a restore drill every month; a RADIUS failover drill every quarter.
- Capacity: FreeRADIUS and PostgreSQL sized for 10× the current subscriber count; `radacct` partitions dropped after 13 months, roll-ups kept.
- Deployments roll one API instance at a time with health checks; schema migrations are backwards compatible for one release.

## Monitoring and alerts

| Signal | Alert when |
|---|---|
| RADIUS Access-Reject rate per tenant | Above 10% of requests for 5 minutes |
| RADIUS response time | p95 above 200 ms |
| Replication lag | Above 30 s |
| M-Pesa callbacks | None for 2 hours in daytime for a tenant with normal volume |
| CoA failures | Any failure after retries |
| RabbitMQ | Queue depth growing for 10 minutes, or any dead-letter message |
| Routers | WireGuard handshake older than 5 minutes |
| Wallet drift | Nightly recompute differs from cached balance |
| Certificates, disk, backups | Standard host alerts; backup older than 26 hours |

Dashboards in Grafana per tenant and for the platform; logs in Loki with `tenant_id` and `correlation_id` on every line.

## Security

- Sign-in and tokens per [ADR 0010](adr/0010-self-issued-jwt-and-sign-in.md): RS256 access tokens of 15 minutes, rotating refresh tokens (reuse revokes the family) in an httpOnly, SameSite=Strict cookie, and a per-request token-version check, so disabling a user, a password or PIN change and a role change take effect at the next request.
- Role-based access for staff with fixed permission codes; TOTP enforced for finance and admin roles (any role holding staff.manage, settings.secrets, billing.adjust, payments.record_manual, payments.assign or payments.reconcile); five failed sign-ins lock an account for 15 minutes.
- Customer portal login by customer number (or phone) and PIN; an SMS code (6 digits, 5 minutes, 3 attempts) sets or resets the PIN; codes limited per phone and per client address, bursts alerted; the message log never holds the code.
- Corebalt platform admins: TOTP mandatory with single-use recovery codes; the first admin is bootstrapped from the environment only while none exists; break-glass reset is a command inside the container, never an HTTP endpoint. Impersonation of a tenant user lasts at most 30 minutes and appears in the tenant's own audit log.
- Audit log of every money movement, access change, secret change and impersonation, append-only and exportable per tenant.
- Router management only over WireGuard; no router API, SNMP or RADIUS port exposed to the public internet; per-router secrets.
- Secrets envelope-encrypted as described in [07](07-multi-tenancy.md); master key outside the database; rotation runbook.
- PPPoE passwords and router RADIUS secrets are the exception: stored readable by FreeRADIUS only ([ADR 0009](adr/0009-radius-credentials-readable-only-by-radius.md)). Because they sit in clear in the primary, the replica and every backup, full-disk encryption on every database host and encrypted backups (WAL archive included) are requirements, not options.
- M-Pesa callback endpoints restricted to Safaricom IP ranges with per-tenant path tokens; all other traffic through Caddy with TLS 1.2+.
- Dependency and container image scanning in CI; least-privilege database roles (FreeRADIUS has read-only access to two views and insert on `radacct`).
- Customer data minimisation: national ID only where the tenant needs it and encrypted at rest; phone numbers masked in logs.

## Compliance

### Kenya Data Protection Act 2019

- Tenant ISPs are data controllers; Corebalt is their data processor. Corebalt registers with the Office of the Data Protection Commissioner (ODPC) and signs a data processing agreement with every tenant before customer data is imported.
- Customer records include the purpose of processing and retention: active data for the life of the subscription, accounting and ledger history for 7 years for tax and lawful-request reasons, then deletion.
- Hosting the core outside Kenya requires documented cross-border transfer safeguards; the Nairobi replica and in-country RADIUS also support an all-Kenya hosting option if a tenant requires it.
- Customers can request their data and its deletion through the tenant; the platform provides export and anonymisation tools per customer.

### Lawful requests

Tenant ISPs may receive requests for which subscriber held an IP address at a time. `radacct` with its 13-month retention and the roll-ups answer this per tenant; exports are produced by the tenant's own admin, logged in the audit trail.

### Tax

- Receipts are sequentially numbered per tenant and never reused, so KRA eTIMS integration in v3 can sign them without renumbering.
- Package prices are stored tax-inclusive with the VAT rate recorded per tenant for reporting.
