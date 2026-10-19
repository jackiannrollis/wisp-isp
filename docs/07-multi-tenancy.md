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

- Customer numbers are the tenant code plus a zero-padded sequence (`CB001238`). The tenant code is unique on the platform, so numbers and PPPoE usernames are unique across all ISPs on the shared RADIUS servers.
- A second line for the same customer is `CB001238-2`.
- M-Pesa account references are the customer number; a tenant's own prefix makes mistyped references easier to recognise and normalise.

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

## Platform layer (Corebalt)

Corebalt staff use a separate platform console, with its own roles, for:

- Tenant onboarding and suspension, trial periods
- Router onboarding scripts and fleet-wide router status
- Platform monitoring: RADIUS success rates per tenant, callback gaps, queue depth
- Billing the ISPs for the platform itself (per active subscriber or flat tiers; open question)
- Impersonation of a tenant admin for support, always logged and visible to the tenant

## Tenant onboarding checklist

1. Create the tenant, code and subdomain; set branding and policies.
2. Start the Daraja app and go-live request immediately; register SMS sender ID.
3. Create packages, staff users and roles; enforce 2FA for finance roles.
4. Import customers from CSV with validation of phone numbers and duplicate numbers; generate PPPoE credentials.
5. Onboard routers with the generated scripts; verify tunnels, RADIUS test logins (`radtest`) and CoA.
6. Run a pilot period with manual payment entry until Daraja is live, then switch on callbacks.
7. Sign the data processing agreement before real customer data is imported (see [08](08-reliability-security-compliance.md)).
