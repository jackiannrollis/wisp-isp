# 06 · Network access control

FreeRADIUS is the only door to the internet: routers ask it, it answers from PostgreSQL, and the platform pushes mid-session changes with CoA. No customer is ever written into router configuration by hand.

## FreeRADIUS design

| Aspect | Design |
|---|---|
| Nodes | Node A at Hetzner reads the PostgreSQL primary; node B in Nairobi reads the streaming replica. Every router lists both. |
| Clients | Loaded from the `radius_client` view: `nasname` is the router's tunnel IP on that node's hub, `secret` is per router. Reloaded on router onboarding. |
| Authorize | `rlm_sql` queries `v_radius_auth` by username. The view computes the access profile from `state`, `expires_at`, `grace_days` and tenant policy at query time and returns the reply attributes. |
| Tenant check | The query also requires `subscription.tenant_id = client.tenant_id`, so one ISP's customer can never log in through another ISP's router. |
| Accounting | `radacct`, partitioned monthly; `Acct-Interim-Interval` 300 s; Gigawords handled. |
| Post-auth | Every Access-Accept and Access-Reject is logged with the profile returned, for support. |
| Password | PPPoE uses PAP or CHAP. Passwords and per-router secrets are platform-generated and stored in `network_radius_credential`, readable only through the two views; the application can set or regenerate them but never read them back ([ADR 0009](adr/0009-radius-credentials-readable-only-by-radius.md)). |

### Profiles and reply attributes

| Profile | `Mikrotik-Rate-Limit` | `Framed-Pool` | `Mikrotik-Address-List` | `Session-Timeout` |
|---|---|---|---|---|
| `FULL` | package `upload/download`, e.g. `5M/10M` | `full` pool of the router | — | seconds to next profile change, max 86400 |
| `THROTTLED` | package throttle speeds | `full` | `throttled` | seconds to next profile change, max 86400 |
| `RESTRICTED` | `512k/512k` | `restricted` pool | `restricted` | 86400 |
| `REJECT` | Access-Reject with `Reply-Message` giving the reason | | | |

Static IPs are returned as `Framed-IP-Address` instead of a pool. `Mikrotik-Rate-Limit` is `rx/tx` from the router's point of view, which is the customer's upload then download.

## PPPoE flow (v1)

1. The customer's router dials PPPoE with username `CB001238` and its password.
2. The MikroTik sends Access-Request to node A, then node B on timeout.
3. FreeRADIUS answers from `v_radius_auth` with the profile above.
4. The MikroTik creates the session with that speed, pool and address list, and starts accounting.
5. At `Session-Timeout`, the router ends the session; the customer router redials and gets whatever profile the dates now give.

Expired users are accepted into `RESTRICTED`, never rejected: a rejected PPPoE client retries endlessly and the customer sees nothing, while a restricted one reaches the pay page.

## CoA and Disconnect

- The network module sends RADIUS CoA (change attributes in place) or Disconnect-Request (drop the session so the client redials) to the router's tunnel IP on UDP 3799, signed with the router's secret.
- Triggers: `SubscriptionRenewed`, `SubscriptionStateChanged`, package upgrade, fair-use threshold.
- Upgrade and throttle use CoA to change `Mikrotik-Rate-Limit` without dropping the session; everything else uses Disconnect so the pool and address list change cleanly.
- Retries: 3 attempts with backoff over 30 seconds; a failure raises an alert and the 24-hour `Session-Timeout` cap guarantees eventual consistency.
- Routers need `/radius incoming set accept=yes port=3799`, which the onboarding script sets.

## Restricted mode, honestly

- Restricted users get an address from the `restricted` pool and the `restricted` address list.
- Router firewall rules for that list: allow DNS, allow the platform's portal and pay-page addresses, redirect TCP 80 to the pay page, drop everything else.
- HTTPS sites cannot be redirected; the customer sees a browser error instead of the pay page. The SMS with a pay link and STK Push therefore carry most of the restore traffic, and the pay page is kept tiny so it loads on anything.
- M-Pesa itself runs over the phone's cellular network, so a customer can pay even while fully cut off.
- The pay page shows the outstanding amount and a one-tap STK Push; after payment it polls the platform and shows "Internet restored" once the CoA has gone through.

## Router onboarding script

Generated per router from the admin dashboard and pasted into the terminal once. It sets:

1. `/system identity` and a `/system note` with the platform router ID
2. A WireGuard interface with peers for hub A and hub B, tunnel addresses from the platform
3. `/radius` entries for both nodes with services `ppp,hotspot`, the per-router secret and `/radius incoming accept=yes`
4. `/ppp aaa use-radius=yes accounting=yes interim-update=5m`
5. The `full` and `restricted` IP pools and the PPPoE server profile that uses RADIUS
6. Firewall address lists and the restricted-mode NAT and filter rules
7. A restricted REST API user for the platform (read, PPP active, system resource) and SNMP read-only with a per-router community
8. NTP so accounting timestamps are right

The platform verifies the script worked by polling `/system/resource` over the tunnel and recording `routeros_version`.

## Hotspot (v2)

- MikroTik hotspot authenticates against the same FreeRADIUS, with `Mikrotik-Group` selecting the hotspot user profile.
- The captive portal is a plain HTML page served by the platform inside the walled garden, offering: buy a plan by phone number (STK Push), enter a voucher, or log back in with phone number and a code.
- Phones randomise their MAC address, so identity is the phone number or voucher; MAC auto-login is a convenience, never the identity.
- Device limits are enforced through the hotspot profile's shared-users setting. On PPPoE they cannot be enforced, because the customer's own router hides its devices.
- Example plans: 1 hour KES 20, 24 hours KES 50, 7 days KES 250, 30 days KES 800.

### Vouchers

- Batches such as 500 × 24-hour; codes like `AF71-KQ82` with a check character; printable PDF sheets.
- Status `UNUSED`, `ACTIVE`, `EXPIRED`, `VOID`; each batch tied to a site or, in v3, an agent for commission.
- Redemption creates a hotspot session whose `expires_at` is redemption time plus plan duration, enforced by the same date logic.

## Accounting and usage

1. Routers send Accounting Start, Interim-Update every 5 minutes and Stop to the RADIUS node that accepted the login. Octets fold in the Gigawords counters (`gigawords << 32 + octets`). The NAS recorded is the address the packet came from (the router's tunnel IP), never the `NAS-IP-Address` the router claims (T3).
2. Rows land in `radacct`, partitioned by month on the start time; a `DEFAULT` partition catches anything outside the created months. A trigger (owner-run) fills `tenant_id`, `router_id` and `subscription_id` from the tunnel address and the username, so FreeRADIUS touches no other table (N2). A retransmitted packet changes nothing; an Interim-Update or Stop whose Start was lost still creates the session.
3. Partitions: a job creates the current month and the next 3, moving any rows the `DEFAULT` partition holds for them, and drops months older than 13 (N7).
4. Stale sessions: a session with no update for 15 minutes is closed with `acctterminatecause = 'Stale'` and its last update as stop time. If the router reports it again, the same row reopens.
5. Every 15 minutes a job adds each session's growth since the last run (a per-session high-water mark, never negative) to `network_usage_daily` for the tenant-local day the job runs in. The portal, dashboard and reports read only `network_usage_daily` (N7).
6. Fair-use: when a subscription's cycle usage passes `fup_bytes`, `UsageThresholdReached` triggers a CoA to the throttle speed until the next renewal.
7. Active sessions for the dashboard are `radacct` rows with no stop time, cross-checked against `/ppp/active` over REST when staff open a router page.

## Router monitoring

- Online means the RouterOS REST API answers `GET /rest/system/resource` over the tunnel. The tunnel worker ([ADR 0011](adr/0011-tunnel-worker.md)) polls every monitored router every 60 seconds with the router's read-only REST login, which staff enter once (stored envelope-encrypted, never returned; T4).
- Three missed polls in a row mark the router `OFFLINE`, publish `RouterOffline` and raise a `ROUTER_OFFLINE` staff alert for that router; the next answer marks it `ONLINE`, publishes `RouterOnline` and clears the alert. A router without a REST login is `NOT_MONITORED`; one never polled yet is `UNKNOWN`.
- Each answer records RouterOS version, board, uptime and CPU load. Accounting activity is only a secondary "last heard" (`last_accounting_at`), never the online signal.
- Staff can ask for a poll now (`RouterPollRequested`). SNMP counters and traffic come later.
- The router page shows status, tunnel IP, CPU, RAM, uptime, online users and traffic, like: Utawala-01 · Online · CPU 23% · RAM 41% · 62 days · 283 users · ↓614 Mbps ↑91 Mbps.
