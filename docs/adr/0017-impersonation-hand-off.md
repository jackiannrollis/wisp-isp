# ADR 0017 · Impersonation hand-off from the console to the admin app

**Status:** Proposed (open question Q17; nothing of this is built)
**Date:** 2026-10-26

## Context

An impersonation (ADR 0010) is a STAFF access token for one active staff member of a tenant, naming the platform admin (`act`) and the impersonation (`imp`), valid at most 30 minutes, with no refresh token, recorded in the tenant's own audit log. The console (ADR 0016) lists and ends impersonations, but the token, once started, is only usable with `curl`: the admin app has no way to receive it, no session without a refresh token, no visible sign that someone from Corebalt is acting, and nothing that ends the session at expiry. The console and the admin app are different origins, so the token has to cross from one to the other.

## Options

**A. A window message with a handshake (recommended).** The console starts the impersonation and opens `https://admin.<domain>/impersonate` in a new window. That page posts "ready" to its opener; the console answers with the token by `postMessage`, addressed to the admin origin only; the admin page accepts a message only from the console's origin (`console.<same domain>`, or the configured development origin) and only once. The token never appears in a URL, the browser history or a server log. The admin app then runs an impersonated session: the token in memory only, no refresh, a banner on every page ("Corebalt support acting as Jane, ends 14:32", with "End now"), and the sign-in page when it expires or is ended.

**B. The token in the URL fragment.** `https://admin.<domain>/impersonate#token=…`. Simpler; the fragment is never sent to a server, but it sits in the browser history and in anything that copies the address, until the page replaces it.

**C. Keep it API-only.** Corebalt support works with `curl` as today.

## What option A changes, if accepted

- Admin app: the `/impersonate` route and its handshake, an impersonated session mode in the auth service (no refresh, expiry timer), the banner from the token's `act` claim, "End now" calling the platform endpoint through the console window or ending locally.
- Console: "Act as" on an active staff member (reason, minutes up to 30), opening the window and answering its handshake.
- Tests: the handshake refuses another origin and a second message; the banner shows; the session ends at expiry; Playwright drives the two windows.
- The default while Q17 is open is C.
