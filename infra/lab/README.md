# Network lab (step 2 of the v1 build order)

Proves the network half of the core flow before any business code exists: a MikroTik router asks
FreeRADIUS, FreeRADIUS answers from PostgreSQL (`v_radius_auth_v2`), expired customers land in a
walled garden with a pay page, and CoA/Disconnect change a live session. See docs/06 and ADRs
0004, 0006 and 0007.

> **Status (2026-10-19).** The Docker half is verified: `RadiusAuthIT` passes locally and in CI, and
> the hub, FreeRADIUS and the pay page run in the compose stack. The Hyper-V half (`lab.ps1` and
> the `routeros/` templates) is written and parses but has **not yet run against real routers**, so
> CoA/Disconnect (N5) and the restricted-mode rules (N3, ADR 0007) are not proven yet. Likely first
> failures: Windows Firewall blocking UDP 51820 or TCP 8089 from the Default Switch, a fresh CHR
> refusing REST with an empty admin password, and two untried RouterOS settings (`/radius
> timeout=1500ms`, the WireGuard peer `name=`).

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

- `RadiusAuthIT` (in `./mvnw verify` and CI) builds the FreeRADIUS image, loads `seed.sql` and
  checks every profile, Session-Timeout and the tenant check over real RADIUS.
- `infra/lab/scripts/seed.ps1` loads the same fixtures into the running stack; then
  `infra\scripts\radtest.ps1 CB000004` shows a RESTRICTED accept from inside the container.

| User | Line | Expected answer |
|---|---|---|
| CB000001 | active, 30 days left | Accept, FULL, `5M/10M`, pool `full`, Session-Timeout 86400 |
| CB000002 | 1 hour past expiry, 3 grace days, throttled grace | Accept, THROTTLED, `1M/2M`, list `throttled` |
| CB000003 | 1 hour past expiry, 3 grace days, full-speed grace | Accept, FULL |
| CB000004 | 10 days past expiry, 2 grace days | Accept, RESTRICTED, `512k/512k`, pool and list `restricted` |
| CB000005 | paused | Accept, RESTRICTED |
| CB000006 | suspended | Reject, "Account suspended" |
| CB000007 | pending | Reject, "Account not yet active" |
| CB000008 | 2 hours left, no grace | Accept, FULL, Session-Timeout about 7200 |
| ZZ000001 | tenant ZZ | Reject through tenant CB's routers (T3); Accept through ZZ's NAS |

Every password is `lab-password`.

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

The onboarding script is `routeros/onboarding.rsc.tmpl`, the eight steps of docs/06 in order;
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
infra\lab\scripts\lab.ps1 login CB000006
```

In the product, steps 3 and 4 are driven by events (`SubscriptionStateChanged`,
`SubscriptionRenewed`) and the network module sends the CoA/Disconnect; here they are manual.

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
