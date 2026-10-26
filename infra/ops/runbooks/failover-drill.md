# Runbook: failover drill

What the platform must survive (docs/08 Failure modes), how a drill proves it, which drills can run today and which wait for the Nairobi node (Q2). docs/08 asks for a RADIUS failover drill every quarter; ADR 0008 for a documented replica promotion and that drill before the second tenant; Gate 2 of docs/09 needs a passed one. Every drill runs on staging first and leaves a record in `drills/`.

## What exists today

One core host runs the whole stack, FreeRADIUS node A and WireGuard hub A (`infra/ops/README.md`, The stack). FreeRADIUS node B, the PostgreSQL replica and hub B in Nairobi are not built: the data centre is not chosen (Q2). Until they exist:

- a RADIUS outage is the "both nodes down" row of docs/08: existing PPPoE sessions stay up, every new login fails, and the 24-hour `Session-Timeout` cap (S4) bounds the damage;
- a hub outage is a RADIUS outage too, because FreeRADIUS listens on the hub's tunnel address only (N6);
- the only thing that fails over is the host itself, from the backups (`backups.md`, procedure A).

So the drills that can run now rehearse the single-node behaviour on staging and prove the alerting and the host rebuild. They are never run on production: stopping the only FreeRADIUS node or the only hub there is a login outage for every tenant. The production RADIUS drill, and Gate 2, wait for node B.

| Drill | Scenario (docs/08) | What must hold | Runs today |
|---|---|---|---|
| A | FreeRADIUS node A down | Sessions stay up; logins go to node B (today: they fail); `CorebaltRadiusProbeDown` fires and resolves | Staging |
| B | WireGuard hub A down | Sessions stay up; CoA fails and retries; tunnels return by themselves; `CorebaltHubDown` fires and resolves | Staging |
| C | The core host is gone | Everything back from the repository on a new host; routers reconnect without being re-onboarded | Staging, on a replacement server |
| D | PostgreSQL primary down | Node B serves logins from the replica; the replica is promoted by the documented procedure | Pending Q2 |
| E | Hetzner unreachable from Kenya | Logins and accounting continue through node B | Pending Q2 |

## Before any drill

- **Staging has a tenant to drill with.** Staging holds no production data and no lab seed; it is onboarded like production, with fake data, by `pilot-onboarding.md`. The drills need: a tenant, a router named `radtest` registered with tunnel address `127.0.0.1` (its RADIUS secret, shown once, is what the login check uses), a package, and one customer with a line on that router activated by a manual payment: the **drill line**. Keep its number and PPPoE password in the password manager item of the staging inventory; nothing else about it is secret.
- **Baseline.** `infra\ops\ops.ps1 monitoring staging`: every target up, no alert but the Watchdog. Open Grafana's `Network: RADIUS and tunnels` dashboard through the SSH tunnel (`monitoring.md`, Reaching it). Note the open sessions (the Sessions screen; on staging there are none unless a real router is peered with it).
- **The window.** Staging: any time, say so in the team channel. Production (once node B exists): at night, in a window the tenants were told about, two people: the operator runs, a witness keeps the record.
- **The record.** Copy `drills/TEMPLATE.md` to `drills/<yyyy-mm-dd>-<inventory>-<letter>.md` and fill it in as you go, times in UTC.
- **Abort rules.** Restore the service at once, and record the drill as failed with the reason, when: an alert outside the expected set fires; anything the drill did not predict happens; on production, a tenant or customer complains, or the witness says stop.

### The login check

The check is manual: a real Access-Request through FreeRADIUS and the database view (docs/06), not the Status-Server probe the monitoring uses (`CorebaltRadiusSlow` measures that round trip only; a synthetic login probe is an open item, see the end). On the host, with the drill line's number and password:

```sh
cd /srv/corebalt/stack
read -rsp 'radtest router secret: ' RADIUS_LAB_SECRET; echo; export RADIUS_LAB_SECRET
sudo --preserve-env=RADIUS_LAB_SECRET docker compose --env-file .env --env-file images.env \
    exec -e RADIUS_LAB_SECRET freeradius corebalt-radtest <number> <password>
unset RADIUS_LAB_SECRET
```

`Received Access-Accept` with the line's profile is a pass; `radtest` exits 1 on a reject and reports no reply when FreeRADIUS is down or does not know the client. The secret is read at a prompt so it stays out of the shell history and the process list; `sudo --preserve-env` relies on the operator's `NOPASSWD: ALL` entry, which implies `SETENV`, and is unproven on a host until the first staging run (the same command works on the local test stack without sudo). The auth log is the other witness: in Grafana's `Logs` dashboard, service `freeradius`, or in Explore:

```
{service="freeradius"} |= "<number>"
sum by (radius_outcome) (count_over_time({service="freeradius"} | radius_outcome=~"OK|incorrect" [1m]))
```

On production the login check is a real one: the ISP's technician redials a customer router they own, or a customer calls, and the auth log shows the accept. No production credential is ever typed on a host.

## Drill A: FreeRADIUS node outage (staging)

Expected on a single node: no login is answered while FreeRADIUS is down; existing sessions are untouched (the router keeps them; nothing in PPPoE needs RADIUS until `Session-Timeout`); accounting packets are retried by the routers and lost after their retries, so `radacct` falls behind and sessions silent for 15 minutes are closed as `Stale` by the API (docs/06 Accounting) and reopened at the next update. The probe timer writes `corebalt_radius_up 0` within a minute; `CorebaltRadiusProbeDown` is pending at once and fires after 5 minutes; the email or webhook follows within Alertmanager's group wait.

1. Record the baseline and run the login check: accepted.
2. T0: `cd /srv/corebalt/stack && sudo docker compose --env-file .env --env-file images.env stop freeradius`.
3. Login check: no reply. In Grafana, `corebalt_radius_up` drops to 0 at the next probe; Prometheus shows the alert pending (its Alerts page through the tunnel, or `monitorctl status`).
4. Wait for the alert to fire (T0 + 5 minutes, plus up to one evaluation) and for the notification to arrive. Record both times. Keep FreeRADIUS down 10 minutes in all, so the notification path is seen end to end.
5. T1: `sudo docker compose --env-file .env --env-file images.env start freeradius`.
6. Login check within a minute: accepted (FreeRADIUS loads the routers at start and answers the first request from the view). Record the first accept after T1.
7. The alert resolves at the first probe after T1 plus one evaluation; the resolved notification follows. Record it. `ops monitoring staging`: no alert but the Watchdog.

Pass: the alert fired within 7 minutes of T0 and resolved within 3 minutes of T1, both notifications arrived, a login was accepted within 2 minutes of T1, and nothing outside the expected effects happened. With node B in place the same drill runs on production and the criterion changes: a login is accepted during the outage, through node B, within the routers' RADIUS timeout (1.5 s per server in the onboarding script), and no session is lost.

## Drill B: hub outage (staging)

Expected on a single hub: `systemctl stop corebalt-wg-hub` deletes `wg0`, so every router's tunnel drops. Customer traffic does not cross the tunnel, so PPPoE sessions continue. RADIUS, accounting, CoA and the worker's polls all need the tunnel: logins fail as in drill A, the worker marks a router `OFFLINE` after three missed polls (about 3 minutes) and opens a `ROUTER_OFFLINE` staff alert, and any CoA or Disconnect attempted meanwhile fails after its retries (about 30 seconds) and opens a `COA_FAILED` staff alert (N5). The probe timer writes `corebalt_wireguard_up 0`; `CorebaltHubDown` fires after 5 minutes, and `CorebaltRouterTunnelStale` for each peered router whose handshake is older than 5 minutes. When the hub returns it comes up with the same key and the same peers (the peer files on the host), routers reconnect at their next keepalive (25 s) and the worker's next poll marks them `ONLINE`.

1. Baseline: `sudo wg show wg0 latest-handshakes` (one line per peer; none on a staging host with no router), the Network dashboard, the routers' status in the admin app.
2. T0: `sudo systemctl stop corebalt-wg-hub`. `ip link show wg0` says the interface is gone.
3. Login check: no reply. After 5 minutes `CorebaltHubDown` fires; with a peered router, `CorebaltRouterTunnelStale` too, and the router page shows `OFFLINE`. Record the times and the staff alerts opened.
4. T1 (after 10 minutes): `sudo systemctl start corebalt-wg-hub`. `journalctl -u corebalt-wg-hub -n 5` shows `wg0` up with its public key and the peers loaded; `sudo corebalt-wg-hub public-key` is unchanged.
5. With a peered router: `sudo wg show wg0 latest-handshakes` shows a fresh handshake within a minute without anyone touching the router (expected from the router's 25-second keepalive; unproven until a real router is peered, the network lab's Hyper-V half has not run); the router page returns to `ONLINE` within two polls; the staff alerts clear.
6. Login check: accepted. The alerts resolve; record the times.

Pass: no router was touched, the hub's public key is the same, the alerts fired and resolved within their windows, a login was accepted within 2 minutes of T1.

## Drill C: the core host is gone (staging, on a replacement server)

This is `backups.md` procedure A run as a rehearsal, plus what that procedure leaves to this runbook: the hub and the routers. It needs a second staging server for the duration (the old one keeps running until the new one is checked, then it is destroyed), the inventory's vault and the key escrow (`key-escrow.md`). Routers trust the hub by its public key and reach it by the endpoint in their onboarding script: the key comes back from escrow, and the endpoint is the DNS name `hub-a.<domain>` (`pilot-onboarding.md`, Routers), so moving the hub is one record change.

What must be in escrow before this can work: the hub's private key (`key-escrow.md`). The peers need nothing: every router's WireGuard public key is in the database (ADR 0013), and the host's router sync (`corebalt-routersync.timer`) adds them all to the new hub within a minute of the stack coming up, so no router is re-onboarded, which is exactly what the drill must show. `sudo corebalt-wg-hub export-peers` on the old host is a convenience for comparing, not something to escrow.

1. Build the replacement as in `README.md`, Order on a new server, with the same inventory: `bootstrap`, `init-disk` (a new LUKS passphrase, into escrow), `provision`. Its hub starts with a new key.
2. Restore the database: `backups.md` A, steps 2 to 6 (deploy the running release, empty the data directory, `backupctl restore`, start, check, `ops backup`).
3. Restore the hub: `sudo systemctl stop corebalt-wg-hub`, write the escrowed key to `/var/lib/wireguard/hub.key` (root, mode 0600), start it, and compare `sudo corebalt-wg-hub public-key` with the escrow note. The peers follow by themselves: `journalctl -u corebalt-routersync -n 3` shows them added at the next minute, `sudo corebalt-wg-hub export-peers` lists them.
4. Move the names: `hub-a.<domain>` and the web names (`<domain>`, `admin`, `pay`, `*`) to the new address. Caddy obtains its certificates again by DNS-01. A router whose script was rendered with an IP address as `HUB_A_ENDPOINT` instead of the name needs, once, on the router: `/interface wireguard peers set [find name=corebalt-hub-a] endpoint-address=<new address>`.
5. Routers reconnect by themselves once the name resolves (RouterOS 7 resolves a DNS `endpoint-address` and is expected to re-resolve it; unproven here until the network lab runs on real routers, so the first drill C on staging checks it with the lab router, or runs the `peers set` command below on every router): `sudo wg show wg0 latest-handshakes` on the new host, the router page `ONLINE`, `CorebaltRouterNeverConnected` does not fire. Login check: accepted, with the drill line's state as it was.
6. Monitoring: `ops monitoring staging` clean; Grafana's Backups dashboard shows the restore and the new backup.
7. Destroy the old server and its volume. Its data disk is encrypted and that passphrase is retired in escrow; its hub key is the same key that now runs on the new host, so the old machine must not stay anywhere you do not control.

Pass: no router re-onboarded, the data back (the drill line, the tenant's customers, the ledger as the drill checks it), a login accepted, backups running from the new host. Record how long the whole rebuild took: that is the platform's recovery time for a lost core, and docs/08 has no number for it yet.

## Drills D and E: pending Q2

Both need the Nairobi node. Nothing of it is built or designed in detail, so this runbook holds only what the procedure will have to cover, and no commands:

- **Where the replica runs and how the core reaches it**: the `nairobi` group of the inventory (its shape is in the example inventory), streaming replication over the hubs' tunnel or a dedicated link, the replication role and its secret in the vault.
- **The decision rule.** A link outage between Hetzner and Kenya is not a reason to promote: node B serves logins and accounting from the replica while writes wait (docs/08, ADR 0008). Promotion is for a primary that is lost or unrecoverable, decided by a person, never automated.
- **The order of a promotion**: stop what still writes to the old primary; promote the replica; point the core's stack at it (or move the core); create the backup stanza anew for the new timeline and take a full backup; rebuild the old primary as a replica. Routers notice nothing: both FreeRADIUS nodes keep answering from the database each reads.
- **Drill D** is that procedure run on a staging replica, and **drill E** is drill A with node A stopped while a router logs in through node B. `CorebaltReplicationLag` (docs/08, pending Q2) is part of the baseline from then on.

They are written when the Nairobi node is built, before the second tenant (ADR 0008).

## The record

One file per drill in `drills/`, from `drills/TEMPLATE.md`, committed with the next change. It names the drill line only, never a customer's number, and holds no secret. Every quarter's records are what Gate 2 and ADR 0008 ask to see.

## Open items

- A synthetic login probe for the monitoring (docs/08 names the real RADIUS response time; `CorebaltRadiusSlow` approximates it with the Status-Server round trip) needs a credential FreeRADIUS accepts on every production node: a probe tenant with a router and a line, which would appear in tenant counts and in the per-subscriber metering of Q1. Decide whether to create one; until then the login check is manual, as above.
- The recovery time for a lost core (drill C) is not stated anywhere; the first rehearsal measures it.
- Drills D and E, and the production RADIUS drill, follow the Nairobi node (Q2).
