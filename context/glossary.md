# Glossary

## Platform terms

| Term | Meaning |
|---|---|
| Tenant | One ISP or Wi-Fi vendor using the platform; owns its customers, routers, packages, staff and M-Pesa credentials |
| Tenant code | Short unique prefix per tenant (`CB`) used in customer numbers and PPPoE usernames |
| Customer | A person or business billed by a tenant; has one wallet and one or more subscriptions |
| Customer number | `<tenant code><6-digit sequence>`, e.g. `CB001238`; also the M-Pesa account reference and the first PPPoE username |
| Subscription (line) | One service connection: package, router, credentials, expiry and state |
| Package | A sellable plan: speeds, duration, price, grace, fair-use and throttle values |
| Wallet | A customer's balance, backed by the ledger |
| Ledger entry | An append-only money movement on a wallet (`PAYMENT`, `RENEWAL_CHARGE`, …) |
| Renewal | Debiting the wallet by the package price and extending `expires_at` by the package duration |
| Grace period | Days after expiry during which access continues (full speed or throttled) before restriction |
| Restricted mode | Walled garden for unpaid lines: portal and pay page reachable, everything else blocked |
| Access profile | Derived result of the date logic: `FULL`, `THROTTLED`, `RESTRICTED`, `REJECT` |
| Display status | What screens and SMS show: `ACTIVE`, `EXPIRING`, `GRACE`, `RESTRICTED`, `PAUSED`, … |
| Suspense queue | Payments whose account reference matched no customer, waiting for staff to assign |
| Reconciliation | Daily comparison of received callbacks with the M-Pesa statement |
| Proration | The charge for an immediate upgrade covering only the remaining days of the cycle |
| Onboarding script | Generated RouterOS commands that connect a router to the platform |
| Outbox | Table where events are written in the same transaction as the business change, then relayed to RabbitMQ |
| Gate | The measurable condition that must pass before the next roadmap phase starts |

## ISP and network terms

| Term | Meaning |
|---|---|
| ISP / WISP | Internet service provider; a WISP delivers it wirelessly from towers |
| PPPoE | Point-to-Point Protocol over Ethernet: the customer's router dials in with a username and password; the main access method for homes |
| Hotspot | Captive-portal Wi-Fi where each device logs in through a web page; apartments, hotels, cafés, schools |
| Captive portal | The login page a hotspot shows before granting access |
| Walled garden | The small set of destinations reachable before login or while restricted |
| RADIUS | Remote Authentication Dial-In User Service: the protocol routers use to ask "may this user connect, with what limits?" and to report usage |
| AAA | Authentication, authorisation and accounting, the three jobs RADIUS does |
| FreeRADIUS | The open-source RADIUS server the platform runs |
| `rlm_sql` | The FreeRADIUS module that reads users and writes accounting through SQL |
| NAS | Network access server: in RADIUS terms, the router asking the question; a `router` row on the platform |
| Access-Request / Accept / Reject | The RADIUS login question and its two answers |
| Reply attributes | Values returned with an Accept, such as speed, pool and session timeout |
| `Mikrotik-Rate-Limit` | MikroTik's RADIUS attribute for speed, written `upload/download` (`5M/10M`) |
| `Framed-Pool` | The router IP pool a session draws its address from |
| `Mikrotik-Address-List` | A firewall list the session's address is added to; used for `restricted` and `throttled` |
| `Session-Timeout` | Seconds after which the router ends the session and the client must log in again |
| CoA | Change of Authorization: a RADIUS message from the platform to the router changing a live session's attributes |
| Disconnect-Request | A RADIUS message that drops a live session so the client redials and gets fresh attributes |
| Accounting (Start / Interim / Stop) | The router's usage reports for a session; stored in `radacct` |
| `radacct` | The standard FreeRADIUS accounting table |
| Gigawords | The RADIUS attributes that extend byte counters past 4 GB |
| Interim-Update | Periodic accounting report during a session (every 5 minutes here) |
| CPE / ONU / ONT | Customer-premises equipment; optical network units for fibre |
| RouterOS | MikroTik's router operating system; v7 has a REST API and WireGuard |
| CHR | Cloud Hosted Router: RouterOS as a virtual machine, used in the test lab |
| WireGuard | The VPN protocol used for the router-to-platform tunnels |
| Hub | A platform-side WireGuard endpoint; hub A at Hetzner, hub B in Nairobi |
| SNMP | Simple Network Management Protocol, used to poll router health |
| FUP | Fair-use policy: a usage threshold after which speed is throttled |
| Cycle | A line's paid period from its last renewal (`cycle_started_at`); fair-use and data-cap usage count within it and reset only when the line renews (N9) |
| Data cap | A hard usage limit per cycle (`data_cap_bytes`); past it the line is restricted to the pay page until it renews |
| Carrier NAT (CGNAT) | Mobile and some fixed networks hiding routers behind shared public IPs; the reason for WireGuard |

## Payments and Kenya terms

| Term | Meaning |
|---|---|
| M-Pesa | Safaricom's mobile money service; the main way Kenyan subscribers pay |
| Paybill | An M-Pesa business number customers pay to, with an account reference identifying what they pay for |
| Till | An M-Pesa buy-goods number without an account reference |
| Account reference (`BillRefNumber`) | What the customer types when paying a Paybill; the customer number here |
| Daraja | Safaricom's M-Pesa developer API platform |
| C2B | Customer-to-business: Paybill payments and their callbacks |
| Validation / Confirmation URL | The two C2B callbacks: optional pre-approval, and the notification of a completed payment |
| STK Push (Lipa na M-Pesa Online) | A payment prompt pushed to the customer's phone with amount and reference prefilled |
| `CheckoutRequestID` | The ID of an STK Push request, matched to its callback |
| `TransID` / `MpesaReceiptNumber` | The unique M-Pesa transaction code; the idempotency key |
| MSISDN | The payer's phone number in international format |
| B2C | Business-to-customer payout, used for refunds later |
| Transaction Status | Daraja API to look up a transaction by its ID |
| Go-live | Safaricom's approval moving a Daraja app from sandbox to production, per tenant shortcode |
| PesaLink | Kenyan bank-to-bank transfer rail |
| Trilho | A payments provider Corebalt may integrate later for collection on behalf of tenants |
| KRA | Kenya Revenue Authority |
| eTIMS | KRA's electronic tax invoice system; integration planned for v3 |
| ODPC | Office of the Data Protection Commissioner, the regulator under the Data Protection Act 2019 |
| Africa's Talking | The SMS gateway used in v1 |
| Matatu | Kenyan minibus; some operators run hotspot Wi-Fi on board |
