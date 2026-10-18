# 01 · Product overview

## What it is

A multi-tenant ISP operating platform, sold by Corebalt as SaaS to Kenyan Wi-Fi vendors, WISPs, fibre ISPs and hotspot operators. Each tenant ISP manages its customers, packages, billing, payments and routers in one place, and the platform controls every subscriber's internet access from their subscription state.

## The principle

**Billing state is network entitlement.**

| Subscription state | Internet |
|---|---|
| Paid, before expiry | On, at package speed |
| Expired, inside the grace period | On (full speed or throttled, per tenant policy) |
| Expired, past grace | Restricted: walled garden with the portal and pay page only |
| Payment received | Restored within seconds, with no staff involved |

Not "a billing application that happens to connect to MikroTik", but "an ISP operating platform where the subscription controls the network".

## The core event chain

Every design choice serves this sequence:

1. `PaymentReceived` — an M-Pesa callback arrives and is deduplicated on its transaction ID
2. `WalletCredited` — the amount lands in the customer's balance
3. `SubscriptionRenewed` — the renewal debits the wallet and extends the expiry date
4. `NetworkAccessChanged` — the RADIUS entitlement switches to the paid profile
5. `SessionReauthorized` — a CoA or Disconnect request makes the router log the customer in again
6. `CustomerNotified` — an SMS confirms the amount and the new expiry date

## Who uses it

| User | Where | Does |
|---|---|---|
| Tenant ISP staff: owner, finance, support, technician | Admin dashboard (Angular) | Manage customers, packages, payments, routers, tickets |
| Subscriber | Customer portal (Angular), SMS | See status and usage, pay, upgrade, raise issues |
| Hotspot user (v2) | Captive portal (plain HTML) | Buy time with M-Pesa or a voucher |
| Agent or reseller (v3) | Agent screens | Sell packages and vouchers for commission |
| Corebalt platform admin | Platform console | Onboard tenants, monitor the fleet, bill ISPs |

## Kenyan market context

- **M-Pesa is the payment rail.** Paybill with the customer number as the account reference identifies the payer automatically; STK Push from the portal removes typing errors.
- **SMS reaches every subscriber**, including restricted ones, because it travels over the cellular network. WhatsApp and email come later.
- **Customer access is mostly MikroTik PPPoE** for homes and businesses; hotspots cover apartments, hotels, restaurants, schools and matatus.
- **Compliance on the horizon:** the Data Protection Act 2019 (ODPC registration, processor agreements) and KRA eTIMS for tax invoices.

## Three rules that shape everything

1. **Redundant, in-country RADIUS.** Corebalt's uptime becomes every tenant's uptime, so there are two FreeRADIUS nodes, one in Nairobi, before a second tenant is onboarded.
2. **Expiry is enforced by RADIUS at login time**, never only by a scheduled job. A stuck scheduler can never leave unpaid users online or paid users offline.
3. **A wallet ledger replaces invoice matching.** Partial payments, overpayments and upgrades fall out naturally instead of needing three configurable rules.

## What v1 is

The smallest product an ISP would pay for: PPPoE billing that collects and reconnects on its own.

- Customers, packages, subscriptions
- Wallet ledger with M-Pesa Paybill and STK Push
- FreeRADIUS PPPoE profiles with automatic restrict and restore (CoA over WireGuard)
- SMS notifications
- Admin dashboard, basic customer portal
- Staff roles, permissions and an audit log

Hotspot, vouchers, support, installations, inventory, agents, accounting reports and eTIMS follow in v2 and v3. See [09 · Roadmap](09-roadmap.md).

## Example packages

| Package | Download / upload | Duration | Price | Grace | Fair use |
|---|---|---|---|---|---|
| Home Basic | 5 / 2 Mbps | 30 days | KES 1,500 | 2 days | Unlimited |
| Home Plus | 10 / 5 Mbps | 30 days | KES 2,500 | 2 days | 500 GB, then 2 Mbps |
| Home Pro | 20 / 10 Mbps | 30 days | KES 4,000 | 2 days | Unlimited |
| Daily Wi-Fi (hotspot, v2) | 5 / 2 Mbps | 24 hours | KES 50 | 0 | — |
| Weekly Wi-Fi (hotspot, v2) | 5 / 2 Mbps | 7 days | KES 250 | 0 | — |

Figures are illustrative; every tenant defines its own packages.
