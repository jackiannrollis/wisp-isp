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

- **The suite runs against an isolated real stack.** Playwright drives all three Angular dev servers on ports 4300 (admin), 4301 (portal) and 4302 (console), which proxy `/api` to the test backend on port 28080 with PostgreSQL, RabbitMQ and Redis, loaded with `infra/lab/seed.sql`. It does not run FreeRADIUS, WireGuard or the tunnel worker: a GitHub runner cannot host the hub, and no spec needs a router. Existing development servers are never reused.
- **A fake Daraja stands in for Safaricom.** `infra/e2e/fake-daraja` is a dependency-free Node server in its own image:
  - production-shaped answers for OAuth, C2B URL registration, STK Push and STK Query;
  - for every push, a success result callback to the request's `CallBackURL` after about 1.5 s, with a unique `CheckoutRequestID` and receipt number (P1 would ignore a repeat);
  - `GET /__pushes` lists what it received, for diagnosis.
- **Isolation (updated 2026-11-03).** `infra/e2e/up.(sh|ps1)` uses `compose.isolated.yml` under the fixed Compose project `corebalt-e2e`. Its database volume, generated signing/encryption keys and fake credentials are separate from the working lab. It never reads or changes `infra/.env` or `backend/.env`. The fake provider on loopback port 28095 also accepts Celcom sends, records test messages through `/__sms`, and returns delivered status for delivery lookups. Both sandbox and production Daraja URLs, and the Celcom URL, point to the fake. Global setup requires the fake's `testOnly` health marker and a test-backend JWT key ID before fixture writes.
- **Test data.**
  - The suite signs in as the lab admin and the lab customer `CB000001` (obvious fakes, local and CI only).
  - It sets fake M-Pesa and Celcom settings for tenant `CB` in the isolated database.
  - Pay Now opens a second line for `CB000001`. The line is PENDING, so it is due, and not FULL, so the page waits for its restore. The suite pays it through the portal and terminates it afterwards.
  - A line still PENDING when the test stops early is cancelled before activation (docs/04); one left behind by an interrupted run is reused, not duplicated.
  - Payments and ledger entries stay within the disposable fixture database, as the ledger is append-only (B1).
  - The console test starts from a fresh database: it changes the bootstrap password, enables TOTP, exercises tenant creation/activation/suspension/reinstatement and cutover controls, verifies support handoff, and checks recovery-code single use. Reset only the isolated project with `infra/e2e/up.ps1 -Fresh -Seed` or `sh infra/e2e/up.sh --fresh --seed` before a complete rerun.
  - Admin coverage includes navigation, desktop/mobile overflow and Celcom account persistence without key disclosure, with payment delivery tracking. Portal coverage includes SMS-code PIN setup, subsequent PIN sign-in, and masking the code in staff notification history.
- **No browser download.** The suite uses the installed Chrome (`channel: 'chrome'`), which GitHub's Ubuntu runners include.
- **CI.** A separate `e2e` job generates independent keys in ignored `infra/e2e/.env`, starts the isolated stack with the seed, type-checks the suite and runs it. It always uploads the Playwright report/screenshots and prints backend and fake-provider logs on failure. The job adds several minutes, mostly the backend image build.

## Consequences

- Sign-in with the refresh cookie, server-side customer search and the whole Pay Now road up to "Internet restored" are proven on every push.
- The Daraja contract is still proven only against fakes. The fake follows the Java `FakeDaraja` of the integration tests and the published shapes, and the sandbox round-trip (`infra/scripts/daraja-sandbox`) remains the check against Safaricom itself.
- Restore is proven only for a line with no open session, where FULL is enough (docs/06). A restore through CoA or Disconnect on a live session needs a router and stays with the network lab.
- Running the suite locally preserves working-lab containers, accounts and provider credentials. The historical `compose.e2e.yml` override remains for reference; the supported entry points use the isolated project.
