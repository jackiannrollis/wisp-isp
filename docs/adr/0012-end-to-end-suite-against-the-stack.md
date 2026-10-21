# ADR 0012 · End-to-end suite against the real stack, with a fake Daraja

**Status:** Accepted
**Date:** 2026-10-21

## Context

conventions.md asks for a small Playwright suite for login, pay-now and customer search. The unit and component tests (Vitest, jsdom) already cover each screen against a mocked `HttpClient`, including the pay page's polling and its 90-second fallback. What they cannot reach is everything between the browser and the database:

- the httpOnly refresh cookie, its path and its Origin check;
- server-side search;
- and the payment road: STK Push → Daraja → result callback → `PaymentReceived` → ledger → renewal → restore status (docs/06).

A suite that mocks the API in the browser would repeat the Vitest tests and prove none of that.

Pay Now cannot run against Safaricom in CI. The backend calls Daraja synchronously when the customer presses the button, and the sandbox needs real keys, a public https callback address and a person to enter a PIN.

## Decision

- **The suite runs against the real stack.** Playwright drives both Angular dev servers, which proxy `/api` to the backend container with PostgreSQL, RabbitMQ and Redis, loaded with `infra/lab/seed.sql`. It does not run FreeRADIUS, WireGuard or the tunnel worker: a GitHub runner cannot host the hub, and no spec needs a router.
- **A fake Daraja stands in for Safaricom.** `infra/e2e/fake-daraja` is a dependency-free Node server in its own image:
  - production-shaped answers for OAuth, C2B URL registration, STK Push and STK Query;
  - for every push, a success result callback to the request's `CallBackURL` after about 1.5 s, with a unique `CheckoutRequestID` and receipt number (P1 would ignore a repeat);
  - `GET /__pushes` lists what it received, for diagnosis.
- **The override is opt-in only.** `infra/e2e/compose.e2e.yml` adds the fake and points the backend at it (`ISP_MPESA_DARAJA_SANDBOXURL`, `CALLBACK_BASE_URL=http://backend:8080`, reached over the compose network, which the `local` profile's callback allowlist already admits). Only `infra/e2e/up.(sh|ps1)` uses it. `infra/scripts/up` starts the normal stack again, pointed at the real sandbox.
- **Test data.**
  - The suite signs in as the lab admin and the lab customer `CB000001` (obvious fakes, local and CI only).
  - It sets fake M-Pesa settings for tenant `CB`. The Daraja sandbox script sets its own again when it runs.
  - Pay Now opens a second line for `CB000001`. The line is PENDING, so it is due, and not FULL, so the page waits for its restore. The suite pays it through the portal and terminates it afterwards.
  - A line still PENDING when the test stops early is cancelled before activation (docs/04); one left behind by an interrupted run is reused, not duplicated.
  - Payments and ledger entries stay, as the ledger is append-only (B1). Locally the lab customer's history therefore grows by one payment per run; CI starts from empty databases.
- **No browser download.** The suite uses the installed Chrome (`channel: 'chrome'`), which GitHub's Ubuntu runners include.
- **CI.** A separate `e2e` job writes an `infra/.env` with throwaway secrets, starts the e2e stack with the seed, type-checks the suite and runs it. On failure it uploads the Playwright report and prints the backend and fake Daraja logs. The job adds several minutes, mostly the backend image build.

## Consequences

- Sign-in with the refresh cookie, server-side customer search and the whole Pay Now road up to "Internet restored" are proven on every push.
- The Daraja contract is still proven only against fakes. The fake follows the Java `FakeDaraja` of the integration tests and the published shapes, and the sandbox round-trip (`infra/scripts/daraja-sandbox`) remains the check against Safaricom itself.
- Restore is proven only for a line with no open session, where FULL is enough (docs/06). A restore through CoA or Disconnect on a live session needs a router and stays with the network lab.
- Running the suite locally replaces the backend container with one pointed at the fake. It must be followed by `infra/scripts/up` before any real sandbox work.
