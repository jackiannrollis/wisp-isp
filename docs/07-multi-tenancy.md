# 07 · Multi-tenancy

One platform serves many ISPs, and nothing crosses between them: every row carries a tenant ID and every router belongs to exactly one tenant.

## Isolation

| Layer | Mechanism |
|---|---|
| Database | `tenant_id` on every tenant-scoped table; PostgreSQL row-level security policies compare it with `current_setting('app.tenant_id')`, which the API sets per transaction from the authenticated principal |
| Application | A `TenantContext` is resolved once per request (JWT claim for staff, subdomain or customer number for the portal, path token for M-Pesa callbacks) and is mandatory in every repository call; a missing context fails the request |
| RADIUS | `v_radius_auth` joins the router's tenant to the subscription's tenant, so a username only authenticates through its own ISP's routers |
| Events | Every event carries `tenant_id`; consumers set the tenant context from it before touching data |
| Reports | Heavy queries run on the read replica with the same RLS policies |
| Rate limits | API limits per tenant in Redis, so one large ISP cannot slow the others |

## Usernames and numbers

- Customer numbers are the tenant code plus a zero-padded sequence (`CB001238`). The tenant code is unique on the platform, so numbers and generated PPPoE usernames are unique across all ISPs on the shared RADIUS servers.
- A second line for the same customer is `CB001238-2`.
- A customer moved from an ISP's old system may keep that system's PPPoE username and password (the cutover import, [ADR 0014](adr/0014-cutover-import-with-current-expiry.md)): such a name is unique within its tenant only and never in the generated shape; FreeRADIUS resolves every username together with the asking router's tenant (T3), so two ISPs may both have an `old-user@isp`.
- M-Pesa account references are the customer number; a tenant's own prefix makes mistyped references easier to recognise and normalise. A customer's old-system reference (`legacy_account_ref`) keeps matching after a cutover (P3).

## Secrets

- M-Pesa consumer key, secret and passkey, SMS API keys, router API and SNMP credentials and TOTP seeds are envelope-encrypted: a per-tenant data key wrapped by a platform master key held outside the database.
- Exception: PPPoE passwords and per-router RADIUS shared secrets must be readable by FreeRADIUS, which reads the database directly (ADR 0004). They are platform-generated, stored in `network_radius_credential` where only the RADIUS views can read them, shown to staff once and then only regenerated, never revealed ([ADR 0009](adr/0009-radius-credentials-readable-only-by-radius.md)).
- Secrets are write-only in the API: entered once, never displayed again, replaced rather than edited.
- Key rotation re-wraps data keys without rewriting ciphertext; a rotation runbook exists before the first paying tenant.
- Secrets never appear in logs, events or error messages; a log scrubber redacts known key shapes as a second line of defence.

## Per-tenant configuration

| Area | Settings |
|---|---|
| Branding | Logo, brand colour, portal subdomain (`acme.corebalt.app`), SMS sender ID |
| Billing policy | Default grace days, grace mode, partial-payment rule, pause limit, currency |
| Packages | The tenant's own catalogue; platform-level templates can be copied |
| Payments | Paybill or Till shortcode, Daraja app, callback registration status |
| Notifications | Templates per event and channel, sending window |
| Staff | Users, roles, permissions, 2FA enforcement |
| Network | Sites, routers, IP pools |

Who changes what: the account's own settings (name, logo, brand colour, default grace days, grace mode, partial-payment rule, pause limit, SMS sender ID and sending window) need `settings.write`, held by the built-in Owner role only; account keys (Daraja, Africa's Talking) need `settings.secrets` and stay write-only (T4); templates need `settings.notifications`. The code, subdomain, time zone and currency are fixed when the tenant is created. Grace and the partial-payment rule apply from each line's next renewal, because a line copies its grace days and grace mode when it renews (S6); the pause limit applies to the next pause; branding applies at once. Every change is audited (A1).

The apps load branding after sign-in, each for the tenant of its own token: staff from `GET /api/v1/branding`, customers from `GET /api/v1/portal/branding`. Before sign-in both show the Corebalt default. The portal takes the provider from the customer number's letters (T2) at PIN sign-in, and asks for the provider code for the SMS-code step. Branding and a prefilled provider by subdomain, before sign-in, are not built yet.

## Platform layer (Corebalt)

Corebalt staff use a separate platform console, with its own roles, for the work below. It is its own Angular app at `console.<domain>` ([ADR 0016](adr/0016-platform-console-app.md)), signing in platform admins only (TOTP mandatory); tenant subdomains may not take the names the platform serves itself (`admin`, `pay`, `console`, `www`, `api`, `hub-a`, `hub-b`).


- Tenant onboarding and suspension, trial periods. A tenant is created in `TRIAL` and moved to `ACTIVE` by the console (`POST /api/platform/tenants/{id}/activate`); `TRIAL` restricts nothing today, the status is bookkeeping for the platform's own billing (Q1). A suspension (`…/suspend`, with a reason; `…/reinstate` to come back as `ACTIVE`) does exactly what reads the status: staff and portal sign-ins are refused and the scheduled jobs skip the tenant; M-Pesa callbacks, events in flight and RADIUS logins continue, so the ISP's customers stay online. Cutting a suspended ISP's customers off is a product decision not taken. Every transition is in the tenant's own audit log and the platform's.
- An Owner's invitation lost before the first sign-in is reissued from the console (`…/staff/{id}/invitation`: a new temporary password shown once, valid 72 hours); once a member has signed in, only the tenant's own staff reset them; an impersonation never manages staff (ADR 0017).
- The cutover window (`PUT …/cutover-window` with an end at most 30 days ahead, `DELETE` to close early; [ADR 0014](adr/0014-cutover-import-with-current-expiry.md)): while it is open, the tenant's Owner may import lines already active with the expiry their customers paid for in the old system (S11). It closes by itself; both audit logs record it; the tenant sees it in its account settings.
- Router onboarding scripts and fleet-wide router status
- Platform monitoring: RADIUS success rates per tenant, callback gaps, queue depth
- Billing the ISPs for the platform itself (per active subscriber or flat tiers; open question)
- Impersonation of a tenant's staff member for support ([ADR 0017](adr/0017-impersonation-hand-off.md)), always logged and visible to the tenant: "Act as" on an active member, with a reason or ticket, for at most 30 minutes; the console gives a link that opens the admin app once within a minute, where a banner shows who acts as whom and the time left, with End. Recording manual payments, wallet adjustments, secrets, the account's settings, staff and `paid_until` imports stay closed whatever the member's role.
- The internal probe tenant (`INTERNAL`, [ADR 0019](adr/0019-synthetic-radius-login-probe.md)): Corebalt's own, holding the two lines of the synthetic RADIUS login probe, created only by the `probe-setup` command. It is never listed, found by code or subdomain, signed into or visited by a scheduled job, and kept out of Gate 1, the per-tenant RADIUS views and the reconciliation report; its lines are the only ones activated with cause `PROBE` (S12).

## Tenant onboarding checklist

1. Create the tenant, code and subdomain; set branding and policies.
2. Start the Daraja app and go-live request immediately; register SMS sender ID.
3. Create packages, staff users and roles; enforce 2FA for finance roles.
4. Import customers from CSV with validation of phone numbers and duplicate numbers; generate PPPoE credentials, or keep the old system's, and for a cutover the expiry each customer already paid for (the admin app's import screen: a preview, then the import).
5. Onboard routers with the generated scripts; verify tunnels, RADIUS test logins (`radtest`) and CoA.
6. Run a pilot period with manual payment entry until Daraja is live, then switch on callbacks.
7. Sign the data processing agreement before real customer data is imported (see [08](08-reliability-security-compliance.md)).

The step-by-step version, with what each role does, the API calls the console has no screen for, the router hand-offs and the cutover choices: `infra/ops/runbooks/pilot-onboarding.md`.
