# Runbook: monitoring and alerts

What watches a staging or core host, how to reach it, how alerts reach you, and what to do when one fires (docs/08 Monitoring and alerts; decisions.md 2026-10-26).

## What runs where

Each stack host runs the monitoring project `infra/monitoring/compose.yml` beside the stack, installed by the `monitoring` role into `/srv/corebalt/monitoring` and run by the unit `corebalt-monitoring` (starts after `corebalt-stack`, stops with it, skipped while the data disk is locked or nothing is deployed). Its data is on the encrypted disk: logs and metrics hold customer numbers and router addresses.

| Service | Does | Keeps |
|---|---|---|
| Prometheus | scrapes every target every 15 s, evaluates `rules/*.yml` | 30 days, at most 10 GB (`/srv/corebalt/prometheus`) |
| Alertmanager | groups and sends alerts: email, optional webhook, the Watchdog to the dead man's switch | silences, notification state |
| Grafana | the dashboards `Corebalt / Platform`, `API and JVM`, `Data stores`, `Network: RADIUS and tunnels`, `Backups`, `Logs`; data sources Prometheus, Loki, Alertmanager (all provisioned, read-only in the UI) | users, preferences |
| Loki | logs; evaluates the LogQL rules in `loki/rules/fake/` and sends them to Alertmanager | 30 days (`/srv/corebalt/loki`) |
| Alloy | ships every container's stdout (through the Docker API, socket mounted read-only) and the host journal of the corebalt units, sshd and docker to Loki; API lines get `tenant_id`, `correlation_id` and `logger` as structured metadata, FreeRADIUS lines their `radius_outcome` | read positions |
| node_exporter | CPU, memory, load, disks, clock of the host, plus the text files in `/srv/corebalt/metrics` (backups, drills, probes). Network interface counters are the container's, not the host's | nothing |
| postgres-exporter | PostgreSQL statistics as `isp_monitor` (`pg_monitor` only; every deploy creates or updates the role from the stack's `.env`) | nothing |
| redis-exporter, blackbox-exporter | Redis statistics; the public names probed from the host (`targets.d/web.yml`: TLS verified, status, certificate expiry) | nothing |
| the stack itself | the API instances and the worker (`/actuator/prometheus`), Caddy (`web:9080`), RabbitMQ (`15692`, its plugin) answer Prometheus on the stack's network; nothing of this is reachable from the internet (the web front answers 404 for `/actuator`) | |
| `corebalt-probes.timer` | every minute, `probes all`: FreeRADIUS's counters and the round trip of a Status-Server request from inside its container (`corebalt_radius.prom`), and the hub's WireGuard peers from `wg0` (`corebalt_wireguard.prom`) | text files for node_exporter |

Every series, log line and alert carries `environment` (the inventory's name) and `host`.

## Reaching it

Nothing is published. From your machine:

```
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 corebalt@<host>
```

then Grafana at http://127.0.0.1:3000 (user `admin`, password `vault_grafana_admin_password`), Prometheus at :9090 (targets, rules, alerts), Alertmanager at :9093 (alerts, silences). Without a tunnel, `infra\ops\ops.ps1 monitoring staging` prints each host's services, scrape targets that are down and alerts firing. On the host:

| Command | Does |
|---|---|
| `/srv/corebalt/monitoring/monitorctl status` | services, targets down, alerts firing |
| `/srv/corebalt/monitoring/monitorctl check` | validates every configuration file with the images' own tools and runs the rule unit tests |
| `/srv/corebalt/monitoring/monitorctl reload` | re-reads the Prometheus, Alertmanager, blackbox and Alloy configurations in place (Loki re-reads its rules by itself, Grafana its dashboards) |
| `/srv/corebalt/monitoring/monitorctl up` / `stop` | what the unit runs |
| `/srv/corebalt/monitoring/probes radius` / `wireguard` | run a probe now and look at the file in `/srv/corebalt/metrics` |
| `docker compose -f compose.yml --env-file .env logs <service>` in `/srv/corebalt/monitoring` | a monitoring service's own log |

Lost the Grafana admin password? `docker compose -f compose.yml --env-file .env exec grafana grafana cli admin reset-admin-password <new>` in the monitoring directory, then put the new one in the vault (`ansible-vault edit`) so the next provision agrees.

## Alert delivery

Configured in the inventory and its vault; `ops provision` writes `alertmanager.yml` (no secrets in it) and the secret files under `secrets/` (uid 65534 only).

| Channel | Settings | Notes |
|---|---|---|
| Email | `monitoring_smtp_host` (host:port), `monitoring_smtp_user`, `monitoring_smtp_from`, `monitoring_alert_email` (default `acme_email`), `vault_smtp_password` | TLS required. Critical alerts repeat every 4 hours, warnings every 12, resolved alerts are sent |
| Webhook | `vault_alert_webhook_url` | Alertmanager's webhook format; a Slack-compatible incoming webhook or ntfy works |
| Dead man's switch | `vault_deadman_url`, `monitoring_deadman_interval` (5 m) | The `Watchdog` alert is re-sent there every 5 minutes. Pick a service that alerts you when the heartbeat stops (a "check" with a grace period longer than 5 minutes), and nothing else. Without it, a dead host is silent |

Neither configured: alerts show only in the UIs on the host; the role says so at provision time.

Test the chain after the first provision: in Prometheus's UI (`:9090/alerts`) the Watchdog is firing; in Alertmanager (`:9093`) it is active and, with a dead man's switch configured, the switch shows recent pings. For email, send a test alert from the host:

```
curl -s -XPOST http://127.0.0.1:9093/api/v2/alerts -H 'Content-Type: application/json' \
  -d '[{"labels":{"alertname":"TestAlert","severity":"warning","host":"'$(hostname)'"},"annotations":{"summary":"Delivery test, ignore"}}]'
```

It arrives within `group_wait` (30 s) and resolves itself after `resolve_timeout` (5 m). `CorebaltAlertNotificationsFailing` fires when a delivery fails; `docker compose ... logs alertmanager` says why.

## When an alert fires

`severity`: `critical` pages (repeat every 4 h), `warning` informs (every 12 h). Every alert's description names the first thing to look at; this table adds the usual causes. The rules are in `infra/monitoring/rules/<file>.yml`.

| Alert | Look at | Usual causes and what to do |
|---|---|---|
| `CorebaltServiceDown`, `CorebaltMonitoringTargetDown` (services) | `stackctl status`, `monitorctl status`, `docker compose logs <service>` | A service crashed or is restarting; a deploy that failed its health gate (the previous release is back, `stackctl status` says so). `postgres` down with the rest of the stack up: `CorebaltPostgresDown` follows |
| `CorebaltApiUpstreamUnhealthy` | `docker compose logs api-1` or `api-2` | One API instance fails Caddy's readiness check; the other serves. A JVM out of memory, a migration still running on `api-1` after a deploy |
| `CorebaltApiErrorRate`, `CorebaltApiErrorLogs` | Grafana, Logs, service `api-*`, level `ERROR`; the `correlation_id` groups one request's lines | A dependency failing (database, RabbitMQ, Redis, Daraja, Africa's Talking) shows as a burst of 5xx on one endpoint. Check `Data stores` first |
| `CorebaltApiSlow`, `CorebaltDbPoolSaturated` | `API and JVM` dashboard (HikariCP), `Data stores` (backends by state, locks) | Long transactions holding connections; a lock on a hot table; the host swapping (`CorebaltMemoryLow`) |
| `CorebaltJvmHeapHigh` | `API and JVM`, JVM heap and GC | A leak or too small a cap (`JAVA_TOOL_OPTIONS` in the compose file); the instance restarts itself on OutOfMemoryError, the other keeps serving |
| `CorebaltPostgresDown` | `docker compose logs postgres`; `psql` as `isp_monitor` from the postgres container | The database is down (then everything is), or the role's password in `/srv/corebalt/monitoring/.env` differs from the stack's `.env` (`ops provision` writes both from the same vault value; `stackctl deploy` resets the role's password) |
| `CorebaltPostgresConnectionsHigh` | `Data stores`, backends by state; `pg_stat_activity` | The API pools (two instances), FreeRADIUS and the worker together near `max_connections`; idle-in-transaction sessions |
| `CorebaltReplicationLag` | the replica's host | Pending Q2: there is no replica yet |
| `CorebaltRabbitDeadLetters` | RabbitMQ UI through `ssh -L 15672:127.0.0.1:15672`, queue `<consumer>.dlq`; the API log for the consumer's error | A consumer failed three times on an event: a bug or a dependency down. Fix the cause, then shovel the messages back to the work queue (RabbitMQ UI, Move messages). The business change behind them did not happen until then |
| `CorebaltRabbitQueueGrowing`, `CorebaltRabbitQueueWithoutConsumers` | `Data stores`, queues table; `docker compose logs` of the owning service (`api-1`, `api-2`: events; `tunnel-worker`: `network.coa`, `network.router-poll`) | The consumer is down or slow. Access never depends on it (E4); restores, SMS and roll-ups catch up when it returns |
| `CorebaltRabbitAlarm` | `docker compose logs rabbitmq`; disk and memory on the host | RabbitMQ blocks publishers under a memory or disk alarm: the outbox relay backs up (`OutboxRelay` keeps the rows, nothing is lost). Free the resource |
| `CorebaltRedisDown`, `CorebaltRedisRejectingConnections` | `docker compose logs redis` | Redis is cache only: sign-in limits and token versions fall back to the database. Restart it; nothing to restore |
| `CorebaltWalletDrift` | the API log (BillingChecks, level ERROR) names the wallets | B4: a cached balance differs from the ledger. The ledger is the truth; find the cause (a write path that skipped the ledger) before correcting the cache |
| `CorebaltPaymentsUncredited` | `Data stores`, dead letters; the API log | P4: `PaymentReceived` not consumed by billing within 5 minutes. Usually `billing.payment-received.dlq` has the events; customers who paid are not restored until it is fixed |
| `CorebaltMpesaCallbacksSilent` | the API log ("No M-Pesa confirmations for tenant"); the tenant's Daraja portal | The C2B URL registration lapsed or Safaricom's addresses changed (`callback-allowed-cidrs`). Reconciliation recovers the payments daily; re-register the URLs |
| `CorebaltNotificationsDead` | the admin app, notifications page of the tenant | Africa's Talking refused the messages: the tenant's credentials or balance, or the provider down. Re-queue from the admin app once fixed |
| `CorebaltStaffAlertsOpen` | the tenant's dashboard | Routers offline or sessions not reauthorized, uncleared for 30 minutes (docs/06). Usually a router or its tunnel; see the tunnel alerts |
| `CorebaltCoaFailed` | `Network` dashboard, tunnels; the worker log | N5: a CoA or Disconnect got no acknowledgement after the retries. The session keeps its old profile until its Session-Timeout (at most a day). The router's tunnel, or its RADIUS secret (regenerate and re-apply the onboarding script) |
| `CorebaltGate1RestoreLate` | the console's tenant page, Gate 1; `Corebalt / Platform`, Gate 1 by tenant; RabbitMQ messages waiting | Gate 1 (ADR 0018): a customer paid by M-Pesa and was restored more than 60 seconds after the callback, or not within 10 minutes. A stuck queue or outbox relay (the renewal came late), or a router that did not answer the Disconnect (`CorebaltCoaFailed`). Note it for the Gate 1 record |
| `CorebaltOtpBursts` | the API log (PortalSignIn) | Someone hammering the portal sign-in code endpoint; the limits hold. Block the source at the web front if it persists |
| `CorebaltRadiusProbeDown` | `docker compose logs freeradius`; `probes radius` by hand | FreeRADIUS is down or wedged: new logins fail, existing sessions continue (docs/08). Routers try node B once it exists (Q2). Restart the service |
| `CorebaltRadiusSlow`, `CorebaltRadiusDroppingRequests` | `Network` dashboard; the host's CPU and memory | FreeRADIUS starved or the database slow (the view behind every login). Routers retransmit; customers see slow logins |
| `CorebaltRadiusRejectRateHigh`, `CorebaltRadiusTenantRejectRateHigh` | `Network` dashboard, rejects per tenant and the rejected logins panel | N3: only PENDING, SUSPENDED and TERMINATED lines and wrong credentials are rejected. A bulk suspension is expected; a router with stale credentials or a misconfigured NAS is not |
| `CorebaltRadiusUnknownClient` | the log line names the address | A router reaching this node from an address not in `radius_client`: not onboarded, a changed tunnel address, or FreeRADIUS not restarted since its row was added (`docker compose restart freeradius`) |
| `CorebaltHubDown` | `systemctl status corebalt-wg-hub`; `journalctl -u corebalt-wg-hub` | `wg0` is gone: every router's tunnel, RADIUS, CoA and polling through this hub are down. Restart the unit |
| `CorebaltRouterTunnelStale` | `Network` dashboard, routers by handshake age; the tenant's router page | The router is off or its uplink is down (sessions continue, CoA fails, the worker marks it OFFLINE). Nothing to do on the platform; the tenant's staff see the router alert |
| `CorebaltRouterNeverConnected` | `sudo wg show wg0` on the hub | A peer added a day ago without a handshake: the onboarding script was not applied, or the router cannot reach the hub's UDP port |
| `CorebaltSiteDown` | `curl -vk https://admin.<domain>/` from elsewhere; `docker compose logs web` | DNS, the web container, or the API behind it. From the host itself the probe goes out and back through the public address, so a cloud firewall rule can also cause it |
| `CorebaltCertificateExpiring`, `CorebaltCertificateExpiringSoon` | `docker compose logs web` lines with `acme`; the DNS provider's token (`vault_acme_dns_api_token`) and zone | Caddy renews 30 days ahead, so a certificate under 14 days means renewal fails. Fix the token or the zone; Caddy retries by itself |
| `CorebaltDiskLow`, `CorebaltDiskCritical`, `CorebaltDiskFillingUp` | `df -h`, `du -sh /srv/corebalt/*`, `docker system df` | The data disk holds PostgreSQL, Loki and Prometheus: a full one stops the database. Old images (`docker system prune`), the Loki and Prometheus retention (`monitoring_*_retention`), old `radacct` partitions (13 months) |
| `CorebaltFilesystemReadOnly` | `dmesg`, `journalctl -k` | A disk error made the kernel remount read-only. Nothing writes; a reboot and `fsck`, and the backups are the fallback (`backups.md`) |
| `CorebaltMemoryLow`, `CorebaltCpuHigh` | `docker stats`, `top` | The JVMs are capped (`MaxRAMPercentage`); Loki and a restore drill are the other large consumers |
| `CorebaltClockSkew` | `chronyc tracking` | chrony lost its sources (firewall, NTP); expiry and Session-Timeout depend on the clock |
| `CorebaltTextfileMetricsBroken` | `ls -l /srv/corebalt/metrics`, `cat` the newest file | A malformed text file from `backupctl` or `probes`; the backup, RADIUS and tunnel alerts are blind until fixed |
| `CorebaltBackup*`, `CorebaltRestoreDrill*` | `backups.md` | |
| `CorebaltAlertNotificationsFailing` | `docker compose logs alertmanager` | SMTP settings or password, the webhook URL |
| `CorebaltRuleEvaluationFailing` | Prometheus UI, Status, Rules | A rule referencing a metric with the wrong type or a syntax Prometheus rejects; `monitorctl check` finds it before a provision |
| `Watchdog` stopped arriving at the dead man's switch | the host: is it up? `systemctl status corebalt-stack corebalt-monitoring`; the data disk locked after a reboot? | A dead or rebooted host (manual unlock mode keeps everything down until `ops unlock`), or the monitoring project down. Nothing else can tell you this |

Silence an alert you are working on in Alertmanager (`:9093`, Silences), with an expiry and a comment; never by removing the rule.

## Changing rules and dashboards

Rules are files in `infra/monitoring/rules` (Prometheus) and `infra/monitoring/loki/rules/fake` (LogQL); every Prometheus rule has a unit test in `infra/monitoring/tests`. Dashboards are the JSON files in `infra/monitoring/grafana/dashboards`, read-only in the UI: a change is a commit (build it in the UI if you like, export the JSON, replace the file). `bash infra/stack/test.sh` runs every check locally (`monitorctl check` on the host does the same); `ops provision` installs the files and reloads the running services without losing data. The provisioned dashboards are re-read within 30 s; `monitorctl reload` applies the rest.

Adding a probe target (a tenant's portal name, an external dependency): `monitoring_probe_urls` in the inventory. Adding a scrape target: `prometheus.yml`, the same on every host.

## Retention and disk

Prometheus keeps 30 days or 10 GB, whichever comes first (`monitoring_prometheus_retention`, `monitoring_prometheus_retention_size`); Loki 30 days (`monitoring_loki_retention`), compacted daily. Both live on the data disk with PostgreSQL, so `CorebaltDiskFillingUp` matters more than usual. Loki's own log volume is dominated by FreeRADIUS auth lines at scale; lower `LOKI_RETENTION` before the disk, not after. Loki protects the disk by itself: at 90% usage it throttles its write-ahead log and the ingester stops accepting lines (Alloy then logs "final error sending batch" and drops them), so a full data disk silences the logs before it stops the database.

## Known limits

- One host watches itself: when it is down or its disk is locked, no alert leaves it. The dead man's switch is the answer; configure one before production.
- The RADIUS response-time rule measures the Status-Server round trip inside the container, not a real login's time (which adds the database view). A real login is checked by hand in the failover drill (`failover-drill.md`, The login check); a synthetic login probe is an open item there, because it needs a credential FreeRADIUS accepts in production.
- `CorebaltReplicationLag` has nothing to measure until the Nairobi replica exists (Q2).
- node_exporter runs on the compose network: host network interface counters are not collected; the hub's per-peer traffic from `probes wireguard` covers the tunnels.
- Email delivery, the webhook and the dead man's switch are not exercised by the stack test; test them once after the first provision (above).
