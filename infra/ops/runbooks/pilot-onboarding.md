# Runbook: pilot ISP onboarding

How a tenant ISP goes from a signed agreement to customers paying and getting online, with the platform as it is today (docs/07 Tenant onboarding checklist; docs/09 step 9; docs/05 M-Pesa integration). Staging is onboarded the same way with fake data, which is what the failover drills use (`failover-drill.md`); the pilot (Q5: who, how many subscribers, when) is not chosen yet, so nothing here names one.

## Who does what

| Role | Where | Does |
|---|---|---|
| Corebalt operator | the host (SSH), the platform console (API only, `/api/platform`), the key escrow | creates the tenant, allocates tunnel addresses, renders onboarding scripts, adds hub peers, reloads FreeRADIUS, watches Grafana |
| ISP Owner | the admin app, `https://admin.<domain>` | settings, secrets, staff, packages; the account's own policy (`settings.write`, docs/07) |
| ISP technician | the admin app (Network), the routers' terminals | registers routers, runs the onboarding script, configures customer routers |
| ISP finance | the admin app (Payments) | manual payments until Daraja go-live, suspense queue, reconciliation |

## Before you start

- **Q5 answered**: the ISP, the subscriber count, the start date, who signs.
- **The data processing agreement is signed** before any customer data is imported (docs/08 Compliance): the ISP is the controller, Corebalt the processor.
- **Production is ready**: provisioned and deployed (`README.md`, Order on a new server), backed up and drilled once, monitoring clean, alerts reaching you, and staging has run for a week before it (decisions.md 2026-10-21).
- **The platform admin can sign in** (below). Its email and first password came from `ops secrets` (`vault_bootstrap_admin_email`, `vault_bootstrap_admin_password`); the account is created at the first start of `api-1` while no platform admin exists (ADR 0010).
- **From the ISP**, before day one: the tenant name and the code they want (2 to 4 letters: it prefixes every customer number and PPPoE username, T2, and never changes); the portal subdomain (`<subdomain>.<domain>`, lower-case letters, digits and hyphens); the Owner's name, email and phone; their Paybill or Till number and whether a Daraja app exists (if not, they create one on the Daraja portal on day one: go-live takes weeks, docs/05); their Africa's Talking account and registered sender ID; the package catalogue (speeds, prices, durations, grace, fair use); the policy choices below; the router list (site, model, WAN, the interface customers connect on, the addressing they use for customers); the customer list as CSV (format in Customers).
- **Decisions the Owner makes** (defaults from `context/open-questions.md`, flagged, not decided here): grace days and grace mode (Q4 default for prepaid: 0 days, `WAIT_FOR_FULL`), the pause limit, the reminder sending window (M2 default 07:00 to 21:00), the message wording (templates).
- **Decisions the operator makes**: the tunnel addresses (Routers), the DNS name of the hub.

## 1. Create the tenant (operator, platform console)

The platform console is an API without a screen; use `curl` from your machine against `https://admin.<domain>`. A platform access token lasts 15 minutes (ADR 0010); sign in again when it expires.

```sh
API=https://admin.<domain>
curl -s -H 'Content-Type: application/json' -d '{"email":"<admin email>","password":"<password>"}' $API/api/platform/auth/login
```

The answer's `status` says what comes next, each step with the previous answer's `challenge`:

| `status` | Next call | Body |
|---|---|---|
| `CHANGE_PASSWORD` (first sign-in, or after a break-glass reset) | `POST /api/platform/auth/password` | `{"challenge":"…","newPassword":"…"}` (12 characters or more) |
| `TOTP_ENROLL` (no authenticator yet) | `POST /api/platform/auth/totp/enroll` | `{"challenge":"…","code":"<6 digits>"}` from the `totpSecret` or `otpauthUri` of the answer; it also carries the **recovery codes, shown once**: put them in the password manager |
| `TOTP_REQUIRED` | `POST /api/platform/auth/totp` | `{"challenge":"…","code":"<6 digits or a recovery code>"}` |
| `SIGNED_IN` | | `accessToken` in the answer: send it as `Authorization: Bearer …` |

Lost the password or the authenticator: the break-glass reset on the host prints a temporary password once and clears TOTP (ADR 0010; audited with your name): `cd /srv/corebalt/stack && sudo docker compose --env-file .env --env-file images.env exec -e ISP_OPERATOR=<your name> api-1 java -jar /app/app.jar reset-platform-admin <admin email>`.

Then the tenant:

```sh
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "code": "ACME", "name": "Acme Networks Ltd", "subdomain": "acme",
  "ownerEmail": "owner@acme.example", "ownerName": "Jane Owner", "ownerPhone": "+2547XXXXXXXX"
}' $API/api/platform/tenants
```

The answer holds the tenant (`status` `TRIAL`) and the Owner's invitation: a **temporary password shown only in this answer, valid 72 hours**, and the Owner's staff id. Give the password to the Owner in person or by phone, never by email or chat, and have them sign in the same day. `GET /api/platform/tenants` lists the tenants.

If the Owner loses the password before the first sign-in, or the 72 hours pass: `POST $API/api/platform/tenants/<tenant id>/staff/<owner staff id>/invitation` (the staff id is in the creation answer, or `GET …/tenants/<tenant id>/staff`) returns a new temporary password, shown once; it works only for a member who has never signed in, and an Owner who has cannot be impersonated or reset from the console: that is the tenant's own business. The tenant stays `TRIAL` until `POST …/tenants/<tenant id>/activate`, which changes nothing in what the tenant can do (docs/07, Platform layer); do it at go-live so the platform's own records are right. `…/suspend` with a reason refuses the tenant's sign-ins and stops its jobs without touching its customers' connections; `…/reinstate` undoes it.

## 2. The Owner's first sign-in and the account settings (Owner, admin app)

At `https://admin.<domain>`: the provider code (`ACME`), the email and the temporary password. The app then asks for a new password (12 characters or more), and, because the Owner role holds permissions that require 2FA (A2), for an authenticator app: scan the QR code or type the secret, enter a code. Staff get no recovery codes, so **invite a second administrator on day one** (step 3): a member who loses their phone is reset by another member with `staff.manage`, and the operator can impersonate an active member for support (`POST /api/platform/tenants/{id}/impersonations`, 30 minutes at most, visible in the tenant's audit log).

Then Settings, in this order:

| Screen | Set | Notes |
|---|---|---|
| Account | default grace days and what happens during grace (full speed or throttled), what a payment below the price does (waits in the wallet or buys whole days), the longest pause; name, logo, brand colour | A new tenant starts on the Q4 defaults for prepaid: 0 grace days, the payment waits in the wallet (`WAIT_FOR_FULL`). Grace and the partial-payment rule apply from each line's next renewal (S6); branding at once |
| SMS account | Africa's Talking username and API key (write-only, T4), the sender ID (11 characters at most), the sending window (default 07:00 to 21:00, M2), sandbox off | The screen shows the delivery-report URL to register in the Africa's Talking account. On staging, SMS goes only to the inventory's allowlist |
| M-Pesa | the shortcode and whether it is a Paybill or a Till, **sandbox on** until go-live, the Daraja consumer key, consumer secret and passkey (write-only) | The screen then shows the callback URLs Safaricom will call: `https://<domain>/callbacks/mpesa/<tenant token>/c2b/confirmation`, `…/c2b/validation`, `…/stk`, `…/txn-status/result` and `…/txn-status/timeout`. Register them with "Register URLs" once the credentials are real (step 8). "Ask before accepting a payment (C2B validation)" stays off: assume the shortcode does not have it (Q10) |
| Messages | review every message's wording and SMS part count; the tenant's own wording may use only the listed placeholders (M3) | A reminder's parts count against the ISP's SMS credit |

## 3. Staff and roles (Owner)

Staff screen: invite each member with their role. Built-in roles: `Owner` (everything, including the account's own settings), `Finance` (manual payments, assigning unmatched payments, STK, reconciliation), `Support` (customers, lines, STK), `Technician` (routers, lines); custom roles pick from the fixed permission codes. A role that holds a finance or admin code requires TOTP at the next sign-in (A2). The invitation is a temporary password shown once, valid 72 hours, or sent by SMS to the member's own phone when the inviter chooses so (Q11); an unused one can be regenerated from the member's page.

## 4. Packages (Owner)

Packages screen, one per plan: name, download and upload speed, duration (a 30-day plan is 43200 minutes), price in shillings (stored as cents, B2), grace days for the plan, fair-use allowance and throttle speeds, hard data cap (N9). A package can be revised later; running cycles keep what they were sold (S6).

## 5. Routers (technician, operator)

Every router joins the platform over WireGuard with a per-router RADIUS secret (ADR 0006, N6); the script the admin app generates configures it (docs/06; ADR 0013) and no customer is ever written into it (N1). The technician registers the router and generates its script; the operator adds the peer on the hub by hand. Per router:

1. **Tunnel address (operator).** Hub A is `wg_hub_address` of the inventory (`10.200.0.1/24` in the example); routers take `/32` addresses from `.11` upward, unique across every tenant. The register of record is the hub itself: `sudo corebalt-wg-hub export-peers` lists every address in use; take the lowest free one. (The platform refuses a duplicate at registration too.)
2. **Register (technician, Network screen).** Name and tunnel address. The answer shows a RADIUS secret once (ADR 0009); it is replaced by the one in the onboarding script, so nothing needs to be noted. Name the router `<site>-<n>` in lower-case letters, digits and hyphens: the hub's peer is named after the tenant code and this name.
3. **Generate the script (technician, router page, "Onboarding script").** The dialog asks for the ISP's addressing on this router: the interface customers dial on, the PPPoE server's own address, the pools FULL and RESTRICTED sessions get addresses from (two different ranges: the restricted-mode rules match the `restricted` list, docs/06) and the ISP's own management network. The platform fills in the rest: the hub (its public key read from the host, `hub-a.<domain>` as the endpoint, so a replacement host is one record change, `failover-drill.md` drill C; that RouterOS re-resolves the name after a change is expected, not yet proven on a real router), the router's tunnel address, where restricted customers may go (the public address `pay.<domain>` resolves to), NTP, and **fresh secrets: a WireGuard key pair generated for the router (the platform keeps the public key), a new RADIUS secret, a new REST password and SNMP community**. The script is shown once with Copy and Download and never stored; treat the file as a secret and delete it once the router has run it. The previous RADIUS secret and REST login stop working when the router runs the script, so generate it when the technician is ready to paste it.

4. **Run it (technician).** Paste the script into the router's terminal once; it is safe to re-run: pools and the profile are updated in place. On a router already serving customers the script keeps the existing PPPoE server (RouterOS allows one per interface) and logs a warning; its `default-profile` is switched to `corebalt-radius` at the cutover (step 6). Nothing has to be read back from the router: the platform already knows its WireGuard public key.
5. **Peer it (operator, core host).** The router's WireGuard public key is on its page: `sudo corebalt-wg-hub add-peer <tenant code>-<router name> <public key> <tunnel address>/32`, then `sudo /srv/corebalt/stack/stackctl reload-radius` so FreeRADIUS loads the new secret (it reads `radius_client_v3` at start only; existing sessions are unaffected). Then refresh the escrowed peer list: `sudo corebalt-wg-hub export-peers` into the password manager item of the hub (`failover-drill.md`, drill C).
6. **Check the tunnel.** Within a minute: `sudo wg show wg0 latest-handshakes` has the router with a fresh handshake; Grafana's `Network: RADIUS and tunnels` dashboard shows the peer; `CorebaltRouterNeverConnected` would fire after 24 hours without one.
7. **Monitor it (router page).** The REST login the script set (`corebalt-api`) is already stored: the tunnel worker polls the router every minute and the page shows `ONLINE` with the RouterOS version within a minute of the tunnel coming up. Whether the script's read-only user may read `/ppp/active` on a real router is unproven until the network lab has run (docs/06).
8. **First login.** The first customer line on this router (step 6) is the login check: the router's `/ppp active` and the platform's Sessions screen show the session, the auth log in Loki shows the accept (`failover-drill.md`, The login check). A reject with "Account not yet active" means the line is still `PENDING`; no reply at all means FreeRADIUS does not know the router: `stackctl reload-radius` again, and compare the secret.
9. **CoA.** The first restore proves it: a line restricted for non-payment, then paid, must be back within a minute without anyone touching the router (docs/06 CoA and Disconnect). Until the network lab has run on real routers, CoA and Disconnect are proven only against a fake router (`infra/lab/README.md`), so the first real restore is watched by the technician with a customer on the phone.

Node B: the script carries the place for the second hub peer and the second RADIUS entry; both are added when the Nairobi node exists (Q2), by re-running an updated script on every router.

## 6. Customers and the cutover (Owner or Support, technician)

**Import.** There is no import screen; a member with `customers.write` and `subscriptions.write` posts the CSV to the API with their own token (sign in as in step 1 at `POST /api/v1/auth/login` with `{"tenant":"ACME","email":…,"password":…}`, then the TOTP step):

```sh
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/csv' --data-binary @customers.csv \
    "$API/api/v1/customer-imports?routerId=<router id>"
```

Columns, header required, any order: `name`, `phone` (required; Kenyan national or international form), `email`, `address`, `installation_location`, `lat`, `lng`, `package` (a package name; a row with one gets a `PENDING` line on the router). At most 5000 rows, one file per router. All rows or none: a bad phone, a duplicate in the file or against existing customers, or an unknown package rejects the whole file with every row's error, so the import is repeated until it passes and never imports twice. The answer lists each customer's number and, for each line, its **PPPoE username (the customer number, T2) and password, shown only in this answer** (ADR 0009). Save the answer to a file in the password manager for the technician and delete it when every customer router is configured; a lost password is regenerated from the line's page (shown once again).

**Customer routers.** The technician sets each customer's PPPoE username and password. A `PENDING` line is rejected by RADIUS (S2) and a rejected PPPoE client redials endlessly while the customer sees nothing (docs/06), so configure a customer's router only once its line is activated.

**Activation is a payment.** The import sets no expiry: a line is `PENDING` until its first renewal is paid (docs/04), and the first payment activates it for one package duration from the moment of payment (S5; checked: a manual payment of the package price moved an imported line from `PENDING` to `ACTIVE` with an expiry 30 days later). For customers the ISP already serves this decides the cutover, and the Owner chooses:

| Cutover | How | Trade-off |
|---|---|---|
| On each customer's renewal date | the customer pays as usual on their due date (manual entry until go-live), the line activates, the technician switches that customer's router the same day | no money lost, a rolling cutover over one billing cycle; the old system runs beside the platform meanwhile |
| On one day | Finance records one manual payment per customer, referenced to their last receipt, and every line activates for a full cycle from that day | one switch for the whole router; the days each customer had already paid for in the old system are given away |
| New customers only | they pay first, as the platform intends | no migration of existing customers |

An "import with the current expiry" would make the one-day cutover lossless; it does not exist (docs/04 keeps every activation on a payment). Open item at the end.

**Manual payments** (Finance, Payments screen, or `POST /api/v1/payments/manual` with an `Idempotency-Key`): customer, amount, channel (`CASH`, `BANK` or `MPESA_PAYBILL` for an M-Pesa receipt entered by hand), a reference, the payer's name. The wallet is credited, the renewal follows (B5), the receipt is issued and the payment SMS goes out at once (M2).

## 7. The pilot period

Until Daraja go-live every payment is entered by hand, so the pilot runs on manual payments (docs/09 step 9). Day to day, the ISP's staff: Payments (what came in, the suspense queue once callbacks exist), the dashboard (lines by status, renewals due, the action queue: lines pending over 14 days), Alerts (SMS credit, sender ID, dead letters, routers offline, CoA failures). Weekly, Finance imports the M-Pesa statement (Payments, reconciliation) so what the platform recorded is compared with what the shortcode received, both ways.

The operator watches Grafana: `Network: RADIUS and tunnels` (the tenant's reject rate, `CorebaltRadiusTenantRejectRateHigh`), the Platform dashboard, and the staff alerts of the tenant (`CorebaltStaffAlertsOpen`). `CorebaltMpesaCallbacksSilent` is silent itself until go-live.

**Gate 1** (docs/09): two billing cycles with no manual reconnects, payment matching above 98% automatic, no restore slower than 60 seconds after the callback. Where the numbers come from, on the host (`cd /srv/corebalt/stack && sudo docker compose --env-file .env --env-file images.env exec -u postgres postgres psql -d isp`, then `\set code 'ACME'`); all three are approximations the API does not yet report:

```sql
-- Payments matched without a staff assignment, last 30 days; manual entries count as staff-matched
select count(*) filter (where status = 'MATCHED' and matched_by is null) as automatic,
       count(*) filter (where status = 'MATCHED' and matched_by is not null) as by_staff,
       count(*) filter (where status = 'UNMATCHED') as unmatched,
       round(100.0 * count(*) filter (where status = 'MATCHED' and matched_by is null) / nullif(count(*), 0), 1) as automatic_pct
from payments_payment
where tenant_id = (select id from tenancy_tenant where code = :'code') and received_at >= now() - interval '30 days';

-- Restore time: a matched payment to the acknowledged CoA or Disconnect of that customer's line, last 30 days.
-- Approximate: only the last CoA per line is kept (network_line_coa), so a line restored twice counts once.
select count(*) as restores,
       round(percentile_disc(0.95) within group (order by extract(epoch from c.last_coa_at - p.matched_at))) as p95_seconds,
       round(max(extract(epoch from c.last_coa_at - p.matched_at))) as max_seconds
from payments_payment p
join subscriptions_subscription s on s.tenant_id = p.tenant_id and s.customer_id = p.customer_id
join network_line_coa c on c.tenant_id = s.tenant_id and c.subscription_id = s.id
where p.tenant_id = (select id from tenancy_tenant where code = :'code') and p.status = 'MATCHED'
  and p.matched_at >= now() - interval '30 days' and c.last_coa_result in ('ACK', 'SESSION_GONE')
  and c.last_coa_at between p.matched_at and p.matched_at + interval '10 minutes';

-- Reconnects that needed hands: CoA failures and routers offline raised in the last 30 days
select kind, count(*) as raised, count(*) filter (where cleared_at is null) as still_open
from notifications_alert
where tenant_id = (select id from tenancy_tenant where code = :'code') and kind in ('COA_FAILED', 'ROUTER_OFFLINE')
  and raised_at >= now() - interval '30 days'
group by kind;
```

## 8. Daraja go-live (Owner, Finance)

When Safaricom approves the app: in Settings, M-Pesa, replace the credentials with the production ones and turn sandbox off, then "Register URLs" (`POST /api/v1/settings/mpesa/register-urls`): the confirmation and validation URLs are registered on the shortcode. Finance pays KES 10 from a staff phone to the Paybill with their own customer number as the reference and watches it arrive as a matched payment within a minute; the STK Push from the portal is tested the same way. From then on manual entry stops for M-Pesa and the daily reconciliation catches the rest (P4, docs/05). C2B validation stays off unless Safaricom enabled it on the shortcode (Q10).

## Not built yet, and open items

- The onboarding-script generator in the admin app (docs/06) and a screen for the platform console; both are API and template work by hand today.
- A reason to be `ACTIVE` rather than `TRIAL`: the status changes nothing yet; it waits for the platform's own billing (Q1).
- Hub peers are added by hand on the host, and a router's WireGuard public key lives only in the hub's peer files (hence the escrowed export).
- An import with each line's current expiry, for a lossless one-day cutover (step 6).
- The template's PPPoE server step on a router that already has one (step 5).
- Whether the read-only REST user may read `/ppp/active` on a real router, and CoA and Disconnect on real routers: the network lab's Hyper-V half has not run (`infra/lab/README.md`).
- Per-tenant dashboards follow once the API's metrics carry tenant tags (docs/08).

## Checklist

| | Done by | Proof |
|---|---|---|
| DPA signed; Q5 recorded | Corebalt | the agreement in the records |
| Tenant created; Owner signed in with TOTP; second administrator invited | operator, Owner | `GET /api/platform/tenants`; the audit log |
| Account policy, branding, SMS, M-Pesa (sandbox), templates set | Owner | Settings screens |
| Packages created | Owner | Packages screen |
| Each router: registered, scripted, peered, `reload-radius`, `ONLINE`, peer list escrowed | technician, operator | router page; `export-peers` in the password manager |
| Customers imported; the answer kept safely, then deleted | Support, technician | the Customers screen; the audit log |
| Cutover chosen and done; first logins and the first restore watched | Owner, technician | Sessions screen; the auth log |
| Pilot running on manual payments; weekly reconciliation | Finance | Payments, reconciliation runs |
| Daraja live: URLs registered, a real payment matched | Owner, Finance | Settings, M-Pesa; Payments |
| Gate 1 measured after two cycles | Corebalt | the three queries above, filed with the drill records |
