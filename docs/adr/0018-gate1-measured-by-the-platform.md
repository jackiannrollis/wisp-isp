# ADR 0018 · Gate 1 measured by the platform

**Status:** Accepted
**Date:** 2026-10-27

## Context

Gate 1 (docs/09) is the end of v1: the pilot ISP completes two billing cycles with no manual reconnects, payment matching above 98% automatic, and no restore slower than 60 seconds after the callback. The pilot-onboarding runbook measured it with three SQL approximations run by hand on the host. Two were weak: the restore time joined a matched payment to the last CoA of any of the customer's lines (`network_line_coa` keeps only the last one, so a line restored twice counted once, and `matched_at` is not when the callback arrived), and matching counted manual cash entries as staff-matched. A line with no live session at the moment it is restored gets no Disconnect at all (AccessChanges: its next login is FULL from the view), so "the router acknowledged" cannot be the only end of a restore.

## Decision

- **The reporting module records the facts from events.** `CreditApplied` (subscriptions) gives a payment that renewed lines; each line whose profile before the renewal was RESTRICTED (`RenewedLine.restored()`, the same test as the "restored" payment SMS) becomes a row of `reporting_restore` when the payment arrived by M-Pesa callback. `callback_at` is when the platform first stored a callback for that payment (`payments_mpesa_callback.received_at`, P2), not M-Pesa's `TransTime`; `renewed_at` is the renewal. `SessionReauthorized` (network) is kept in `reporting_reauthorization`, because it may arrive before the credit's event. Both consumers are idempotent (E2); nothing is published.
- **A restore is the platform's part done**, at the first of: the renewal itself when the line had no live session then (`NO_SESSION`: no Disconnect is sent, the next login is FULL); the router answering the Disconnect or CoA (`REAUTHORIZED`) or saying the session had already ended (`SESSION_GONE`); a new session of the line (`NEW_SESSION`, from `radacct`, which also covers a router that never answered while the customer redialled). Only what happens within 10 minutes of the renewal counts; a restore still open after that is **missed**. A job completes open restores of the last 7 days every minute (and every read does first), so the order of events never matters and a job outage shorter than that loses nothing.
- **Matching** counts payments that arrived by callback (any `payments_mpesa_callback` row points at them): automatic is `MATCHED` without a staff assignment, by staff is `MATCHED` with one, and `UNMATCHED` is still in suspense. Manual payments are not matching and are left out. "Above 98%" is judged on the counts (automatic × 100 > total × 98); the percentage shown is rounded to one decimal.
- **Reconnects needing hands** has no direct signal: a reconnect done on the router itself leaves no trace in the platform. The proxy is the `COA_FAILED` and `ROUTER_OFFLINE` staff alerts raised in the period plus the missed restores.
- **Where it shows:** `GET /api/platform/tenants/{id}/gate1?days=1..90` (PLATFORM tokens only; default 30) and the console's tenant page, with each criterion met, not met, or nothing to judge yet (no callback payment, no completed restore, or nothing at all happened); gauges per tenant code for the last hour and 30 days (`isp_gate1_restores`, `isp_gate1_restores_late`, `isp_gate1_restore_p95_seconds`, `isp_gate1_matching_automatic_ratio`) on the Platform dashboard; `CorebaltGate1RestoreLate` (warning) when a tenant had a restore slower than 60 seconds or missed in the last hour.
- **Only RESTRICTED lines are restored.** A first payment that activates a PENDING line, and a renewal that ends a grace throttle or a fair-use throttle, are not counted. A line RESTRICTED for any reason a renewal lifts (expired past grace, or a data cap reached) counts: the customer paid and expects to be back online.

## Consequences

- Gate 1 numbers exist from this release on; no pilot ran before it, so the runbook's SQL approximations are gone. Manual payments count for neither matching nor restores, so the two billing cycles count from the pilot's Daraja go-live.
- The measure is the platform's part. A customer whose equipment takes a minute to redial after the Disconnect is restored at the router's answer; that time is not measured.
- The numbers are visible to Corebalt only (console, monitoring). Whether an ISP's staff should see them is open question Q18; Corebalt only is its default.
- `reporting_reauthorization` grows by one row per CoA or Disconnect; nothing purges it yet (a few thousand rows a month per pilot-sized tenant).
