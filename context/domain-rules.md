# Domain rules

Invariants the code must never break. Each has an ID so tests, reviews and commits can cite it. The long explanations are in the `docs/` page named beside each group.

## Tenancy (docs/07)

- **T1** Every tenant-scoped row carries `tenant_id`; every query runs with a tenant context; a missing context is an error, never "all tenants".
- **T2** Customer numbers and PPPoE usernames are `<tenant code><6-digit sequence>` (`CB001238`), unique across the platform; a second line is `CB001238-2`.
- **T3** A username authenticates only through routers of its own tenant (`v_radius_auth` joins router tenant to subscription tenant).
- **T4** Tenant secrets are write-only: stored envelope-encrypted, never returned, logged or placed in events. Exception (ADR 0009): PPPoE passwords and router RADIUS secrets are stored readable only through the RADIUS views, because FreeRADIUS must compare them; the application can set and regenerate them but never read them back, and shows a new value once.

## Subscriptions (docs/04)

- **S1** Only `PENDING`, `ACTIVE`, `PAUSED`, `SUSPENDED`, `TERMINATED` are stored. `EXPIRING`, `GRACE`, `RESTRICTED` and the access profile are derived from dates at read time, never persisted.
- **S2** Access profile logic, in this order: `PENDING`/`SUSPENDED`/`TERMINATED` → `REJECT`; `PAUSED` → `RESTRICTED`; `now < expires_at` → `FULL`; `now < expires_at + grace_days` → `FULL` or `THROTTLED` by tenant `grace_mode`; otherwise `RESTRICTED`.
- **S3** The same logic exists in the SQL function `subscription_access()` (used by the `v_radius_auth` view and the staff list view `v_subscription_status`) and in `AccessPolicy` / `DisplayStatus`; a test proves they agree for the same inputs, profile, next change and display status. Change both or neither.
- **S4** Every accepted login carries `Session-Timeout` = seconds until the next profile change, capped at 86400.
- **S5** A renewal extends from the current `expires_at` when the line is not yet past grace, and from the moment of payment when it is. The clock time of `expires_at` is preserved.
- **S6** `grace_days` is copied onto the subscription at each renewal; a policy change never shortens a cycle already paid for.
- **S7** Pause freezes the clock: on resume `expires_at += now − paused_at`. Only an `ACTIVE` line with time remaining can be paused, for at most the tenant's `max_pause_days`; a paused line is `RESTRICTED`, not rejected. At `pause_until` the line resumes by itself: the RADIUS view and `AccessPolicy` treat it as `ACTIVE` with `expires_at + (pause_until − paused_at)` from that instant (E4); the job only records it, with cause `AUTO_RESUME_RECORDED`, which needs no CoA.
- **S8** Lifting a suspension re-evaluates dates; a line that expired during the hold returns as `RESTRICTED`.
- **S9** Every state transition writes an audit row and publishes `SubscriptionStateChanged`.
- **S10** Downgrades always apply at the next renewal; immediate upgrades charge proration and change speed by CoA.

## Billing (docs/05)

- **B1** The ledger is append-only. Corrections are `REVERSAL` or `ADJUSTMENT` entries with a reason and an actor; no entry is ever updated or deleted.
- **B2** Amounts are integer minor units (KES cents) in a `long`; floating point is never used for money.
- **B3** Credits are positive, charges negative; `balance_after_minor` is written in the same transaction under a wallet row lock.
- **B4** `wallet.balance_minor` is a cache of the latest `balance_after_minor`; a nightly job recomputes it and alerts on drift.
- **B5** A prepaid renewal fires only when `balance ≥ price` (or by the tenant's `PRO_RATA_DAYS` rule); partial amounts wait in the wallet.
- **B6** Proration = (new price − old price) × remaining days ÷ duration days, rounded up to the shilling; never negative, never refunded mid-cycle.
- **B7** Receipt numbers are per-tenant sequences that are never reused or reissued.

## Payments (docs/05)

- **P1** (`tenant_id`, `provider_txn_id`) is unique; a duplicate callback is acknowledged with success and has no side effects.
- **P2** The raw callback payload is stored before any processing; processing is retried from the stored copy.
- **P3** Matching order: exact account reference → normalised reference → the payer's number, only when it arrives plain or SHA-256-hashed and belongs to exactly one customer → `UNMATCHED` (suspense queue). A masked number (`2547 ***** 126`) is never matched automatically; staff see a shortlist of customers whose number fits it and assign with an audited click. Never a fuzzy name match.
- **P4** `PaymentReceived` is written to the outbox in the same transaction as the payment record. Billing alone credits the ledger from it (one `PAYMENT` entry per payment, enforced by a unique ledger reference) and issues the receipt; a matched payment with no ledger entry after 5 minutes raises an alert. Manual payments take the same road.
- **P5** A manual payment needs the `payments.record_manual` permission, a reference and an audit row.
- **P6** An STK request with no callback after 2 minutes is queried; after 5 minutes it is `TIMED_OUT` and never silently retried.

## Network (docs/06)

- **N1** Routers are configured by generated onboarding scripts; no customer is ever written into router configuration.
- **N2** FreeRADIUS reads only `radius_client` and `v_radius_auth`, and writes only `radacct`.
- **N3** Expired users are accepted into `RESTRICTED`; `Access-Reject` only for `REJECT` profiles.
- **N4** `Mikrotik-Rate-Limit` is `upload/download` (router rx/tx), e.g. `5M/10M`.
- **N5** CoA is used for speed changes in place; Disconnect for changes of pool or address list. Three retries with backoff, then an alert.
- **N6** All router management (RADIUS, CoA, REST, SNMP) travels only over WireGuard; no router port is exposed publicly.
- **N7** `radacct` is partitioned monthly, kept 13 months; `network_usage_daily` is the only usage source for screens and reports.
- **N8** Device limits are enforced only on hotspot (`shared_users`); PPPoE cannot see devices behind the customer's router.

## Notifications (docs/04)

- **M1** Every notification has a `dedupe_key`, unique per tenant, and the same key is never queued twice: reminders use event, subscription and `expires_at`; the payment message uses the payment ID (one SMS per payment).
- **M2** Reminders respect the tenant sending window (default 07:00 to 21:00, tenant time zone); the payment message (including its restored variant), the unrecognised-reference reply and package-change messages go out immediately.
- **M3** Messages never include secrets, PINs or full national IDs: templates, including a tenant's own wording, may use only a fixed list of placeholders that carry none of them.

## Events and jobs (docs/02)

- **E1** Business change and outbox row commit in one transaction; the relay publishes afterwards.
- **E2** Consumers are idempotent by event UUID and set the tenant context from the event before touching data.
- **E3** Events are past tense, `<Aggregate><Verb>`, versioned, and carry IDs rather than entities.
- **E4** Access to the internet never depends on a scheduled job or the broker; both only speed things up. One bounded exception: renewing a prepaid line from credit already in the wallet happens in the hourly job up to `min(24 h, package duration / 4)` before expiry, so only a job outage longer than that window can restrict a customer whose wallet holds the price.

## Identity and audit (docs/08)

- **A1** Every money movement, access change, secret change and impersonation writes an append-only audit row with actor, before and after.
- **A2** Finance and admin roles require 2FA; permissions are fixed codes checked server-side, never only in the UI.
- **A3** Logs mask phone numbers and never contain secrets, passwords or PINs.
