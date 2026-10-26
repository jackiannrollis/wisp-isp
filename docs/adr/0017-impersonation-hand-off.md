# ADR 0017 · Impersonation hand-off from the console to the admin app

**Status:** Accepted (Q17 resolved 2026-10-27; built in the slice after Gate 1's amendment)
**Date:** 2026-10-26, accepted 2026-10-27

## Context

An impersonation (ADR 0010) is a STAFF access token for one active staff member of a tenant, naming the platform admin (`act`) and the impersonation (`imp`), valid at most 30 minutes, with no refresh token, recorded in the tenant's own audit log. The console (ADR 0016) lists and ends impersonations, but the token, once started, is only usable with `curl`: the admin app has no way to receive it, no session without a refresh token, no visible sign that someone from Corebalt is acting, and nothing that ends the session at expiry. The console and the admin app are different origins, so something has to cross from one to the other.

## Options considered

**A. A window message with a handshake.** The console answers a "ready" message from the admin window with the token by `postMessage`, origin-checked both ways.

**B. The token in the URL fragment.** Simple, but the token sits in the browser history and in anything that copies the address.

**C. Keep it API-only.** Corebalt support works with `curl`.

## Decision (the user's, 2026-10-27)

The console opens the admin app as the impersonated staff member, under these constraints:

- **Hand-off by a single-use code**, valid 60 seconds, which the admin app exchanges for the impersonation token. No token in a URL, no refresh token, no cookie: the token lives in the admin app's memory and dies with the tab or at the 30-minute cap.
- **Starting needs a reason or a ticket reference**, and writes the start and end audit rows already specified, with the `act` claim on every action.
- **A permanent banner:** "Corebalt support acting as <name> · ends in mm:ss", with an End button.
- **Blocked while impersonating, whatever the impersonated role holds:** recording manual payments, wallet adjustments, credit notes and refunds, secrets, `settings.write`, staff and role changes, and `paid_until` imports. Support may look and fix operational things; money and access policy stay with the tenant. The server enforces it, not only the screen (A2).

## Consequences

- Admin app: an `/impersonate` route that exchanges the code and replaces its own address, an impersonated session mode in the auth service (no refresh, expiry timer), the banner from the token, End calling the platform's end endpoint.
- Console: "Act as" on an active staff member (reason or ticket, minutes up to 30), opening the admin app with the code.
- Backend: the code (stored hashed, single use, 60 seconds), the exchange endpoint, the permissions removed from impersonation tokens and refused again at the endpoints.
- Tests: a code used twice or after 60 seconds is refused; the blocked actions answer 403 under impersonation even for an Owner; the banner and the expiry; the end-to-end suite drives the hand-off.
