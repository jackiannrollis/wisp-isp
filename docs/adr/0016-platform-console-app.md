# ADR 0016 · The platform console: a third Angular app at `console.<domain>`

**Status:** Accepted
**Date:** 2026-10-26

## Context

docs/07 asks for a separate platform console, with its own roles, for Corebalt staff. The platform API existed (`/api/platform`: tenants, status transitions, the cutover window, invitation reissue, impersonation, sign-in with mandatory TOTP and its own refresh cookie path, ADR 0010), but no screen: the pilot-onboarding runbook drove it with `curl`. The web front serves `admin.<domain>`, `pay.<domain>` and, under the wildcard, `<tenant>.<domain>` portals; nothing stopped a tenant from taking `admin` or `pay` as its subdomain, which would have made its portal unreachable.

## Decision

- **A third Angular app, `console`**, in the frontend workspace beside `admin` and `portal`, served by the web front at `console.<domain>` (its own site block, before the wildcard, with the same security headers and API proxy as the others) and in development on port 4202. It signs in platform admins only, through `/api/platform/auth` with its own refresh-cookie path; it shares no session or code with the admin app, only the generated API types. A separate origin keeps a platform session out of the pages ISP staff use, and the admin app stays tenant-branded and tenant-scoped.
- **Screens:** sign-in (new password, authenticator set-up with the recovery codes shown once, code or recovery code); tenants (list with status and cutover window, create with the Owner's temporary password shown once); a tenant's page (activate, suspend with a reason, reinstate; open, move or close the cutover window; staff of every status with "reissue invitation" for an invitation never used; recent impersonations with "end").
- **Reserved subdomains:** `admin`, `pay`, `console`, `www`, `api`, `hub-a` and `hub-b` are refused as a tenant's portal subdomain (400 naming the list).
- **API change:** the console's staff list returns every status (an `INVITED` Owner is the one whose invitation may need reissuing); `status` narrows it.
- **Not in this decision:** starting an impersonation from the console. Its token must reach the admin app on another origin, which is a trust decision of its own (proposed in ADR 0017, open question Q17); until then it is started through the API. Since built: "Act as" on a tenant's page ([ADR 0017](0017-impersonation-hand-off.md)).

## Consequences

- The web image carries three apps; the stack test checks the console host (the app, a deep link, the platform API through it) and the monitoring probes it.
- DNS: `console` resolves through the same wildcard record as the tenant portals, or gets its own record where the zone has none; it gets its own certificate like `admin` and `pay`.
- The console's origin is allowed for the platform refresh cookie (`ISP_ALLOWED_ORIGINS`, already matched by the stack's one-label wildcard and listed explicitly).
- A tenant that already had a reserved subdomain would keep it; none exists (the pilot has not been onboarded).
