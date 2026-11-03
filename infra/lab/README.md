# Network lab (step 2 of the v1 build order)

Proves the network half of the core flow before any business code exists: a MikroTik router asks
FreeRADIUS, FreeRADIUS answers from PostgreSQL (`v_radius_auth_v7`), expired customers land in a
walled garden with a pay page, and CoA/Disconnect change a live session. See docs/06 and ADRs
0004, 0006 and 0007.

> **Status (2026-11-02).** The Hyper-V lab now runs on two MikroTik CHR 7.24.5 VMs. WireGuard,
> PPPoE, the full and restricted profiles, CoA/Disconnect, and automatic restoration after a fake
> cash payment are verified against RouterOS. The payment restored a new full-speed session in
> 4.287 seconds (4.150 seconds on the final repeat), with one ledger credit, one renewal debit,
> one receipt and an idempotent replay. The one-minute Session-Timeout and suspended, pending and
> cross-tenant login rejections also passed. This is a manual
> payment, outside Gate 1. Celcom SMS and Daraja C2B sandbox credit/renewal are now verified in
> `providers-validation-2026-11-02.md`, alongside a successful KES 1 STK sandbox callback/credit/receipt.
> The combined Daraja callback-to-CHR test also passed: FULL was observed in 4.357 seconds,
> with the platform recording 3.328 seconds; see `daraja-restore-validation-2026-11-02.md`.
> Provider connection recovery was subsequently confirmed live; see `daraja-state-validation-2026-11-03.md`.
> Physical ISP routers and node B remain separate.
> See [the validation record](validation-2026-11-02.md) for the checks and limitations.

```
 Hyper-V                                         Docker Desktop (infra/docker-compose.yml)
 ┌────────────────────┐   WireGuard UDP 51820    ┌──────────────────────────────────────────┐
 │ corebalt-lab-router│ ───────────────────────► │ wireguard (hub 10.200.0.1)               │
 │ CHR, 10.200.0.11   │   RADIUS 1812/1813,      │  └ freeradius (same network namespace) ──┼─► postgres
 │ ether1 WAN (Default│   CoA/Disconnect 3799    │      radius_client_v2, v_radius_auth_v2  │   (lab tables)
 │ Switch, NAT)       │   inside the tunnel      │ caddy :8089  lab pay page                │
 │ ether2 PPPoE server│                          └──────────────────────────────────────────┘
 └─────────┬──────────┘
           │ private switch corebalt-lab-lan
 ┌─────────┴──────────┐
 │ corebalt-lab-client│  CHR as the customer's router: PPPoE client on ether2,
 │                    │  management only on ether1 (no default route there)
 └────────────────────┘
```

## What is automated and tested without the VMs

- `RadiusAuthIT` (in `./mvnw verify` and CI) builds the FreeRADIUS image, loads `seed.sql` into the
  real tables (tenants, packages, customers, routers, lines, credentials) and checks every profile,
  Session-Timeout and the tenant check over real RADIUS against `v_radius_auth_v7`.
- `infra/lab/scripts/seed.ps1` loads the same fixtures into the running stack; then
  `infra\scripts\radtest.ps1 CB000004` shows a RESTRICTED accept from inside the container.

| User | Line | Expected answer |
|---|---|---|
| CB000001 | active, 30 days left | Accept, FULL, `5M/10M`, pool `full`, Session-Timeout 86400 |
| CB000002 | 1 hour past expiry, 3 grace days; tenant CB throttles in grace | Accept, THROTTLED, `1M/2M`, list `throttled` |
| CB000003 | active, static IP 10.10.0.53 | Accept, FULL, `Framed-IP-Address` instead of a pool |
| CB000004 | 10 days past expiry, 2 grace days | Accept, RESTRICTED, `512k/512k`, pool and list `restricted` |
| CB000005 | paused, 10 days of pause left | Accept, RESTRICTED |
| CB000006 | suspended | Reject, "Account suspended" |
| CB000007 | pending | Reject, "Account not yet active" |
| CB000008 | 2 hours left, no grace | Accept, FULL, Session-Timeout about 7200 |
| ZZ000001 | tenant ZZ | Reject through tenant CB's routers (T3); Accept through ZZ's NAS |
| ZZ000002 | 1 hour past expiry, 3 grace days; tenant ZZ keeps full speed in grace | Accept through ZZ's NAS, FULL |
| ZZ000003 | paused 10 days, pause ended an hour ago, not recorded; expired 2 days ago before the shift | Accept through ZZ's NAS, FULL: the view resumes it (S7, E4) |

Every password is `lab-password`.

FreeRADIUS loads its clients (`radius_client_v3`) only at start. After registering a router or
regenerating its secret through the API (`POST /api/v1/routers`, `.../radius-secret`), run
`docker compose restart freeradius` in `infra/` (seed.ps1 does this for the fixtures). Lines and
PPPoE passwords need no restart: they are read on every login.

## Running the VM lab (Windows, Hyper-V)

Prerequisites, once:

1. Hyper-V enabled, and your account in "Hyper-V Administrators" (elevated PowerShell:
   `Add-LocalGroupMember -Group "Hyper-V Administrators" -Member $env:USERNAME`, then sign out and
   in). Without that, run the commands below from an elevated PowerShell.
2. In `infra/.env`: `LAB_CHR_ADMIN_PASSWORD` (any lab value; set on the CHRs at first contact).
3. The stack up and seeded: `infra\scripts\up.ps1`, then `infra\lab\scripts\seed.ps1`.

```powershell
infra\lab\scripts\lab.ps1 create      # downloads RouterOS CHR 7.24.5 (SHA-256 pinned), two Gen 1 VMs
infra\lab\scripts\lab.ps1 onboard     # lab base + onboarding script, hub peer, fixtures, dial CB000001
infra\lab\scripts\lab.ps1 status      # tunnel, PPPoE sessions, queues, address lists
infra\lab\scripts\lab.ps1 check       # FULL: real sites over HTTP and HTTPS
```

After creating the VMs, non-administrators may supply their current management addresses to
`onboard`, `status`, `login`, `check`, `set-line`, `coa`, `disconnect` and `session-timeout`, e.g.
`lab.ps1 check -RouterAddress 192.168.207.224 -ClientAddress 192.168.194.168`. These example addresses
are from the recorded run; use the addresses printed by `create` on your machine. Without overrides,
the script discovers them through Hyper-V, which requires an elevated shell or group membership.

The onboarding script is the platform's own template, `backend/src/main/resources/routeros/onboarding.rsc.tmpl` (the API renders it for real routers, ADR 0013; the lab renders it the same way with a key pair from the hub container), the eight steps of docs/06 in order;
`routeros/lab-base.rsc.tmpl` is what a real ISP router already has (WAN, NAT, resolver).

## Demo: the core flow on the network side

```powershell
# 1. Paid customer: full speed. The router's dynamic queue shows 5M/10M (upload/download, N4).
infra\lab\scripts\lab.ps1 login CB000001
infra\lab\scripts\lab.ps1 check

# 2. Upgrade in place with CoA: same session, new speed (N5, S10).
infra\lab\scripts\lab.ps1 coa CB000001 2M/4M

# 3. The line expires past grace. The live session keeps its profile until Session-Timeout (S4);
#    a Disconnect makes the client redial, and RADIUS now puts it in RESTRICTED (N3, ADR 0007).
infra\lab\scripts\lab.ps1 set-line CB000001 '-10 days'
infra\lab\scripts\lab.ps1 disconnect CB000001
infra\lab\scripts\lab.ps1 check       # DNS works, HTTP shows the pay page, HTTPS and ping fail

# 4. Payment restores access: extend the line and disconnect; the redial comes back FULL.
infra\lab\scripts\lab.ps1 set-line CB000001 '30 days'
infra\lab\scripts\lab.ps1 disconnect CB000001
infra\lab\scripts\lab.ps1 check

# Rejected profiles never get a session (the client keeps retrying, as docs/06 warns):
infra\lab\scripts\lab.ps1 login CB000006 -ExpectReject
```

In the product, steps 3 and 4 are driven by events (`SubscriptionStateChanged`,
`SubscriptionRenewed`) and the network module sends the CoA/Disconnect. The sequence above is manual;
to verify the actual payment road instead, leave CB000001 expired and connected in RESTRICTED, then run:

```powershell
infra\lab\scripts\payment-restore.ps1 -RouterAddress <router-management-IP>
```

It signs into the localhost API as the seeded lab admin, records a fake cash payment for the
renewal shortfall, and waits up to 60 seconds for the real tunnel worker to reconnect the CHR into
FULL. It sends no Disconnect and does not edit the expiry. It checks the new session ID, full queue,
single payment credit and receipt, and replays the same idempotency key. Results go to the ignored
`infra/lab/out/payment-restore.result.json`. This is not a Daraja round-trip or Gate 1 measurement.

For a genuine sandbox callback through the same restricted CHR session, use
`infra/lab/scripts/daraja-restore.ps1 -RouterAddress <router-management-IP>`. This needs the public
HTTPS callback tunnel, sandbox credentials in `backend/.env`, and an expired/restricted CB000001
visible in accounting. It simulates the renewal shortfall, makes no post-payment Disconnect,
and checks the new FULL session, one credit/charge/receipt, and the platform's CUT_OFF restoration
record within 60 seconds. See [the combined validation](daraja-restore-validation-2026-11-02.md)
for commands, timings and the provider-state follow-up. This is a sandbox observation, not the
pilot's two-cycle Gate 1.

`lab.ps1 session-timeout` sets CB000001's expiry one minute ahead, redials it into FULL, then waits
for the router to redial itself into RESTRICTED without a Disconnect. It leaves the line expired;
run the payment check afterwards to restore it. Results go to `out/session-timeout.result.json`.

## Limits of the lab

- The free CHR licence caps each interface at 1 Mbit/s upload. `Mikrotik-Rate-Limit` is proven by
  the router's queue and session attributes, not by throughput.
- The routers are managed over plain HTTP REST on the Default Switch (lab only). Production
  routers accept management only through the tunnel (N6); the onboarding template takes the
  allowed management networks as a parameter for that reason.
- One hub and one RADIUS node. Production adds hub B and node B (ADR 0008) where the template says.
- HTTPS cannot be redirected to the pay page; that is a property of HTTPS, not a lab gap (docs/06).
- `lab.ps1 destroy` removes the VMs and the private switch; the downloaded image stays in
  `infra/lab/images/` (git-ignored).
