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
- Nightly full backups plus continuous WAL archiving for point-in-time recovery, encrypted at rest and in transit to storage; a restore drill every month; a RADIUS failover drill every quarter. In place: pgBackRest from the database container to Object Storage in the EU, WAL within five minutes, 30 days kept, the drill restoring the newest backup into a scratch instance and checking the schema, the row counts and the ledger (`infra/ops/runbooks/backups.md`). The failover drill is `infra/ops/runbooks/failover-drill.md`: with one node today it rehearses the RADIUS and hub outages and the loss of the core host on staging only; the production RADIUS drill and the replica promotion wait for the Nairobi node (Q2).
- Capacity: FreeRADIUS and PostgreSQL sized for 10× the current subscriber count; `radacct` partitions dropped after 13 months, roll-ups kept.
- Deployments roll one API instance at a time with health checks; schema migrations are backwards compatible for one release.

## Monitoring and alerts

Each core and staging host runs its own monitoring project beside the stack (`infra/monitoring`: Prometheus, Alertmanager, Grafana, Loki, Alloy, node_exporter and exporters for PostgreSQL, Redis and the public names; RabbitMQ, Caddy and the API serve their own metrics), installed by Ansible on the encrypted disk and reached through an SSH tunnel only. Alerts go out by email and, optionally, a webhook; the always-firing `Watchdog` goes to a dead man's switch, the only way a single host can report that it, or its monitoring, is down. The rules live in `infra/monitoring/rules` (Prometheus, unit-tested with promtool) and `infra/monitoring/loki/rules` (LogQL on the logs); the runbook is `infra/ops/runbooks/monitoring.md`.

| Signal | Alert when | Rules | Status |
|---|---|---|---|
| RADIUS Access-Reject rate per tenant | Above 10% of requests for 5 minutes, with at least 20 requests | `CorebaltRadiusTenantRejectRateHigh` (Loki, from the auth log; the tenant is the username's prefix, T2); `CorebaltRadiusRejectRateHigh` for the node as a whole | In place |
| RADIUS response time and logins | p95 above 200 ms; a real login failing or getting the wrong profile | `CorebaltRadiusSlow`: the Status-Server round trip inside the FreeRADIUS container, above 200 ms for 5 minutes; `CorebaltRadiusLoginProbeFailed` (critical, per hub and line) and `CorebaltRadiusLoginProbeMissing`: the synthetic login probe of each hub's tunnel worker, the probe tenant's FULL and RESTRICTED lines every minute ([ADR 0019](adr/0019-synthetic-radius-login-probe.md)), with its round trip in `isp_probe_radius_login_seconds` | In place; node B's probe once node B exists (Q2) |
| RADIUS availability | No answer for 5 minutes; requests dropped; a router FreeRADIUS does not know | `CorebaltRadiusProbeDown`, `CorebaltRadiusDroppingRequests`, `CorebaltRadiusUnknownClient` (Loki) | In place |
| Replication lag | Above 30 s | `CorebaltReplicationLag` | Pending Q2: fires once a replica reports |
| M-Pesa callbacks | None for 2 hours in daytime for a tenant with normal volume | `CorebaltMpesaCallbacksSilent` (the backend's gauge) | In place |
| CoA failures | Any failure after retries | `CorebaltCoaFailed` (the tunnel worker's counter); `CorebaltStaffAlertsOpen` when a router or CoA alert stays uncleared for 30 minutes | In place |
| RabbitMQ | Queue depth growing for 10 minutes, or any dead-letter message | `CorebaltRabbitQueueGrowing`, `CorebaltRabbitDeadLetters`, `CorebaltRabbitQueueWithoutConsumers`, `CorebaltRabbitAlarm` | In place |
| Routers | WireGuard handshake older than 5 minutes | `CorebaltRouterTunnelStale` (per peer of the hub's `wg0`), `CorebaltRouterNeverConnected`, `CorebaltHubDown` | In place |
| Wallet drift | Nightly recompute differs from cached balance | `CorebaltWalletDrift` (B4); `CorebaltPaymentsUncredited` (P4) | In place |
| API | 5xx above 5% for 10 minutes; p95 above 1 s; threads waiting for a database connection; heap above 90% | `CorebaltApiErrorRate`, `CorebaltApiSlow`, `CorebaltDbPoolSaturated`, `CorebaltJvmHeapHigh`; `CorebaltApiErrorLogs` (Loki) | In place |
| Services | A metrics endpoint unreachable for 10 minutes; an API instance failing the web front's health check; PostgreSQL or Redis unreachable | `CorebaltServiceDown`, `CorebaltApiUpstreamUnhealthy`, `CorebaltPostgresDown`, `CorebaltPostgresConnectionsHigh`, `CorebaltRedisDown` | In place |
| Certificates, disk, host | A certificate under 14 days (3: critical) or a public name not answering; a disk under 15% (5: critical) or full within a day; memory, CPU, clock, a read-only filesystem | `CorebaltCertificateExpiring`, `CorebaltCertificateExpiringSoon`, `CorebaltSiteDown`, `CorebaltDiskLow`, `CorebaltDiskCritical`, `CorebaltDiskFillingUp`, `CorebaltMemoryLow`, `CorebaltCpuHigh`, `CorebaltClockSkew`, `CorebaltFilesystemReadOnly` | In place |
| Backups | The newest good backup older than 26 hours, a failed run, the monthly restore drill failed or not run for 40 days | `CorebaltBackupStale`, `CorebaltBackupFailed`, `CorebaltBackupMetricsMissing`, `CorebaltRestoreDrillFailed`, `CorebaltRestoreDrillOverdue` | In place |
| Gate 1 (ADR 0018) | A restore slower than 60 seconds after the callback in the last hour (warning); a restore missed (10 minutes without completion) or three slow restores of one tenant in an hour (critical) | `CorebaltGate1RestoreSlow`, `CorebaltGate1RestoreMissed`, `CorebaltGate1RestoresSlowRepeated` (the backend's per-tenant gauges, kept by api-1 every minute) | In place |
| Notifications | SMS given up after retries; bursts of portal sign-in codes | `CorebaltNotificationsDead`, `CorebaltOtpBursts` | In place |
| The monitoring itself | A notification not delivered; a rule group failing; the heartbeat stopping | `CorebaltAlertNotificationsFailing`, `CorebaltRuleEvaluationFailing`, `Watchdog` to the dead man's switch | In place; the dead man's switch is an external service the operator chooses |

Dashboards in Grafana for the platform (`Corebalt / Platform`, `API and JVM`, `Data stores`, `Network: RADIUS and tunnels`, `Backups`, `Logs`); per-tenant dashboards follow once the API's metrics carry tenant tags. Logs in Loki for 30 days: every API line with `tenant_id` and `correlation_id` as structured metadata (stream labels stay few: service and level), the FreeRADIUS auth log with its outcome, and the host's corebalt units.

## Security

- Sign-in and tokens per [ADR 0010](adr/0010-self-issued-jwt-and-sign-in.md): RS256 access tokens of 15 minutes, rotating refresh tokens (reuse revokes the family) in an httpOnly, SameSite=Strict cookie, and a per-request token-version check, so disabling a user, a password or PIN change and a role change take effect at the next request.
- Role-based access for staff with fixed permission codes; TOTP enforced for finance and admin roles (any role holding staff.manage, settings.secrets, settings.write, billing.adjust, payments.record_manual, payments.assign, payments.reconcile, finance.read or subscriptions.import_active; the last and settings.write are the built-in Owner role's alone); five failed sign-ins lock an account for 15 minutes.
- Customer portal login by customer number (or phone) and PIN; an SMS code (6 digits, 5 minutes, 3 attempts) sets or resets the PIN; codes limited per phone and per client address, bursts alerted; the message log never holds the code.
- Corebalt platform admins: TOTP mandatory with single-use recovery codes; the first admin is bootstrapped from the environment only while none exists; break-glass reset is a command inside the container, never an HTTP endpoint. Impersonation of a tenant user lasts at most 30 minutes and appears in the tenant's own audit log.
- Audit log of every money movement, access change, secret change and impersonation, append-only and exportable per tenant.
- Router management only over WireGuard; no router API, SNMP or RADIUS port exposed to the public internet; per-router secrets.
- Secrets envelope-encrypted as described in [07](07-multi-tenancy.md); master key outside the database; rotation runbook.
- PPPoE passwords and router RADIUS secrets are the exception: stored readable by FreeRADIUS only ([ADR 0009](adr/0009-radius-credentials-readable-only-by-radius.md)). Because they sit in clear in the primary, the replica and every backup, full-disk encryption on every database host and encrypted backups (WAL archive included) are requirements, not options.
- M-Pesa callback endpoints restricted to Safaricom IP ranges with per-tenant path tokens; all other traffic through Caddy with TLS 1.2+.
- Dependency and container image scanning in CI; least-privilege database roles (FreeRADIUS has read-only access to two views and insert on `radacct`; the monitoring exporter has `pg_monitor` and reads no table).
- Metrics and logs stay inside the host: the API's metrics endpoint, Caddy's and RabbitMQ's are reachable on the stack's network only and answer 404 from the internet; Grafana, Prometheus and Alertmanager listen on the loopback for an SSH tunnel; the log shipper reads the Docker API through a read-only socket mount. Logs and metrics hold customer numbers and router addresses, so they live on the encrypted disk with 30-day retention.
- Customer data minimisation: national ID only where the tenant needs it and encrypted at rest; phone numbers masked in logs.

## Compliance

### Kenya Data Protection Act 2019

- Tenant ISPs are data controllers; Corebalt is their data processor. Corebalt registers with the Office of the Data Protection Commissioner (ODPC) and signs a data processing agreement with every tenant before customer data is imported.
- Customer records include the purpose of processing and retention: active data for the life of the subscription, accounting and ledger history for 7 years for tax and lawful-request reasons, then deletion.
- Hosting the core outside Kenya requires documented cross-border transfer safeguards; the Nairobi replica and in-country RADIUS also support an all-Kenya hosting option if a tenant requires it.
- Backups are part of that transfer. The pgBackRest repository in Hetzner Object Storage (the EU, the host's region) holds every table, so personal data and, inside the encrypted archive, the RADIUS credentials that ADR 0009 keeps in clear. The safeguard recorded for it: every backup and WAL segment is encrypted on the host before it leaves (AES-256-CBC, pgBackRest repository encryption) with a passphrase held in the inventory's vault and in key escrow, never by the storage provider; transport is TLS; the bucket's credentials belong to one environment; backups are kept 30 days; the monthly restore drill runs on the encrypted data disk and wipes its copy; no copy leaves the EU. Staging holds no production data (docs/02), so its backups carry none. The record of processing and each tenant's processing agreement name Hetzner (data centre and Object Storage) as the sub-processor and this safeguard (`infra/ops/runbooks/backups.md`).
- Customers can request their data and its deletion through the tenant; the platform provides export and anonymisation tools per customer.

### Lawful requests

Tenant ISPs may receive requests for which subscriber held an IP address at a time. `radacct` with its 13-month retention and the roll-ups answer this per tenant; exports are produced by the tenant's own admin, logged in the audit trail.

### Tax

- Receipts are sequentially numbered per tenant and never reused, so KRA eTIMS integration in v3 can sign them without renumbering.
- Package prices are stored tax-inclusive with the VAT rate recorded per tenant for reporting.
