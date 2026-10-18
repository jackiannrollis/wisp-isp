# ADR 0007 · Expired subscribers get a restricted profile, not an Access-Reject

**Status:** Accepted
**Date:** 2026-10-17

## Context

The simplest enforcement is to reject expired users at RADIUS. A rejected PPPoE client redials endlessly, the customer sees a dead connection with no explanation, and support calls follow. Restricting instead of rejecting gives the customer a pay page.

## Decision

Past grace, FreeRADIUS accepts the login into a `RESTRICTED` profile: a `restricted` address pool, the `restricted` address list and a low rate limit. Router firewall rules allow DNS and the platform's portal and pay page, redirect HTTP to the pay page and drop everything else. `Access-Reject` is reserved for `PENDING`, `SUSPENDED` and `TERMINATED` lines.

## Consequences

- An expired customer sees why the internet stopped and can pay with one tap; payment triggers a Disconnect and the next dial gets the full profile.
- HTTPS sites cannot be redirected and show a certificate error; the SMS with a pay link and STK Push are the primary restore path, and the pay page is kept tiny.
- Restricted sessions consume router resources; the low rate limit and the `restricted` pool keep that bounded.
- Pause uses the same profile, so a paused customer can resume from the portal.
