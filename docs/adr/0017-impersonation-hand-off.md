# ADR 0017 · Impersonation hand-off from the console to the admin app

**Status:** Accepted (Q17 resolved 2026-10-27) and built (2026-10-27; "As built" below)
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

## As built

**The code.** 32 random bytes, base64url (43 characters). Only its SHA-256 is stored (`identity_impersonation.handoff_code_hash`, unique), with `handoff_expires_at` (start + 60 seconds) and `handoff_used_at`; of these, the application role may update only `handoff_used_at`. The exchange is one `UPDATE … RETURNING` that marks the code used only while it is unused, unexpired, and its impersonation neither ended nor past its 30 minutes, so two tabs racing for one code cannot both win. Every refusal is the same 401 with one message, whichever condition failed. The start answer (`POST /api/platform/tenants/{id}/impersonations`) carries the code and `openUrl`, `<isp.admin-app-url>/impersonate#code=<code>` (`ISP_ADMIN_APP_URL`, `https://admin.<domain>` on the hosts), never a token, and is `no-store`.

**The exchange.** `POST /api/v1/auth/impersonation {code}` on the admin host answers the token, its expiry (the impersonation's), the staff member's name and the platform admin's name, with `Cache-Control: no-store` and no `Set-Cookie`. The code travels in the fragment, which browsers never send to a server, so it is in no access log; the admin app's `/impersonate` page takes it out of the address bar and the history entry before it calls the exchange.

**Enforcement, two layers.**
1. The permission codes `payments.record_manual`, `billing.adjust`, `settings.secrets`, `settings.write`, `staff.manage` and `subscriptions.import_active` (`Permissions.BLOCKED_WHILE_IMPERSONATING`) are left out of the token when it is issued, and dropped again whenever a token with `act` is read, so no impersonation token can carry them, whatever it was issued with.
2. What sets or reveals a secret under a permission support keeps refuses with 403 while `act` is present (`CurrentActor.requireNotImpersonating`): opening a line (it shows the first PPPoE password), a new PPPoE password, the customer import (it opens lines with passwords; its `paid_until` part also needs `subscriptions.import_active`), registering a router, a new RADIUS secret, the REST login, the onboarding script. The SMS settings answer without the delivery-report URL, whose token is a secret.

Credit notes and refunds have no endpoint or permission code yet; when they come, their codes join the list. Left open, as operational work: assigning a suspense payment (`payments.assign`, audited, P3) and reconciliation, STK requests, Disconnect, pause, suspend, terminate, package changes, router retirement, creating and editing customers.

**The admin app.** The impersonated session lives in memory only: no refresh is ever attempted, a 401 or the expiry ends it, and the tab moves to an "Impersonation ended" page. The banner sits above the top bar with a countdown by the second. End calls `POST /api/v1/impersonation/end` with the impersonation's own token (the platform's end endpoint needs a platform token the admin app does not have), and signing out does the same. The screens hide what the server refuses (`PermissionService.mayHandleSecrets` and the stripped codes); the server stays the authority. A reload loses the session; the impersonation stays open on the server, without any token, until it is ended in the console or expires. In a browser that also holds a staff member's own session cookie for the admin host, the reload restores that staff session instead, with no banner.

**The console.** "Act as" on an active member of a tenant that is not suspended: a reason or ticket (up to 500 characters) and the minutes (1 to 30). The dialog then shows the link, which opens the admin app in a new tab (`noopener`) and works once within the minute.

**Tests.** `ImpersonationHandoffIT`: a code, not a token; single use; refused after 60 seconds, after End and when unknown; nine blocked actions 403 for an impersonated Owner, the SMS URL withheld, operational work allowed, the audit row's actor PLATFORM on behalf of the Owner; End from the admin side. The admin app's unit tests cover the countdown, the fragment, the in-memory session, no refresh, and End. The end-to-end suite does not drive the hand-off: it would need a platform admin with an authenticator on the e2e stack. The stack test drives it on the real web front instead: start from the console API, exchange through the admin host (no cookie, `no-store`), the code refused a second time, a 403 for the Owner's M-Pesa secrets, End, then the token refused.
