# 09 · Roadmap

Ship v1 to one pilot ISP before building anything else; each later phase starts only when the previous gate passes.

```
 v1 · Sellable core        ◇ Gate 1         v2 · Hotspot and portal   ◇ Gate 2        v3 · Operations
 PPPoE billing, pilot ISP                    First paying tenants                      Scale and field work
```

## v1 · Sellable core

The smallest product an ISP would pay for: PPPoE billing that collects and reconnects on its own.

| Area | Scope |
|---|---|
| Tenancy and identity | Tenant, branding, staff users, roles, permissions, 2FA, audit log |
| Customers and packages | Profiles, PPPoE credentials, CSV import, package catalogue |
| Subscriptions | Lifecycle states, renewals, grace, pause, package change with proration |
| Billing | Wallet ledger, receipts, arrears view, manual payments |
| Payments | M-Pesa Paybill callbacks, STK Push, suspense queue, daily reconciliation |
| Network | Router onboarding scripts, WireGuard hubs, two FreeRADIUS nodes, profiles, CoA, restricted mode, accounting ingest, daily usage, fair-use throttling (moved from v2, 2026-10-20) |
| Notifications | SMS for expiring, due, grace, restricted, payment received, restored; router-offline alerts to staff |
| Admin dashboard | Subscribers by state, expected and collected revenue, routers online, active sessions, customer and payment screens |
| Customer portal | Status, package, expiry, usage, Pay Now, payment history, receipts |

**Gate 1:** the pilot ISP completes two billing cycles with no manual reconnects, payment matching above 98% automatic, and no restore slower than 60 seconds after callback.

## v2 · Hotspot and portal

| Area | Scope |
|---|---|
| Hotspot | MikroTik hotspot on the same RADIUS, captive portal, plans, phone-number login |
| Vouchers | Batches, printable sheets, redemption, status |
| Self-service | Upgrade and downgrade, PIN change, outage reports, relocation and pause requests |
| Support | Tickets, technician assignment, outage notices to affected customers |
| Network monitoring | Sites, SNMP metrics, richer router pages |
| Messaging | WhatsApp and email on the same templates |
| Billing | Credit notes and refunds with approval; cards and PesaLink via a gateway |

**Gate 2:** three paying ISPs on the platform, a passed RADIUS failover drill, and support tickets per 100 subscribers trending down for two months.

## v3 · Operations

| Area | Scope |
|---|---|
| Installations and technicians | Site survey, scheduling, assignment, equipment issue, activation flow |
| Inventory | Routers, ONUs, CPEs, cable and switches with serials, tracked to customer locations |
| Agents and commissions | Agent accounts, sales and voucher attribution, commission ledger, payouts |
| Accounting and reports | Revenue, collections, arrears ageing, expenses, VAT, KRA eTIMS |
| Platform | Tenant billing automation, Go telemetry service if polling volume requires it |

## Out of scope until a tenant asks

UniFi, Cisco or Cambium as access routers; multi-currency; bank reconciliation beyond CSV; IPTV or VoIP bundles.

## Suggested v1 build order

Each step ends with something demonstrable; the network lab comes early because it is the biggest unknown.

1. **Foundation.** Repository, modular monolith skeleton, Flyway, tenant context and RLS, Angular shell with auth, CI, Docker Compose with PostgreSQL, RabbitMQ, Redis and FreeRADIUS.
2. **Network lab.** MikroTik CHR in a VM, WireGuard hub, FreeRADIUS reading a hand-written `v_radius_auth`, `radtest` logins, CoA and Disconnect proven, restricted-mode firewall rules proven.
3. **Customers and packages.** CRUD, CSV import, PPPoE credential generation; the view now reads real tables.
4. **Subscriptions.** States, date logic, `AccessPolicy` and the view agreeing under test, reminders scheduler, Session-Timeout behaviour verified in the lab.
5. **Wallet and ledger.** Entries, renewal on credit, proration, manual payments, receipts.
6. **M-Pesa.** Daraja sandbox for C2B and STK, callback idempotency, matching cascade, suspense queue, reconciliation import.
7. **Notifications.** Templates, SMS provider, dedupe keys, sending window.
8. **Dashboard and portal.** The screens in the table above, roles and audit visible.
8b. **Fair use.** After the admin screens: a line's usage in its billing cycle, from `network_usage_daily` (N7), compared with its package's `fup_bytes`; past it the line is THROTTLED to the package's throttle speeds until the cycle renews, decided in the RADIUS view and in `AccessPolicy` together (S3) so a reconnect keeps the throttle; a CoA applies it to the live session (N5); one SMS tells the customer; staff and the portal see usage against the allowance. The hard `data_cap_bytes` follows the same path. Open questions Q12 to Q15 hold the defaults.
9. **Production.** Hetzner core, Nairobi node, backups, alerts, failover drill, pilot ISP onboarding with manual payments until Daraja go-live.
