# Domain rules

Invariants the code must never break. Each has an ID so tests, reviews and commits can cite it. The long explanations are in the `docs/` page named beside each group.

## Tenancy (docs/07)

- **T1** Every tenant-scoped row carries `tenant_id`; every query runs with a tenant context; a missing context is an error, never "all tenants".
- **T2** Customer numbers are `<tenant code><6-digit sequence>` (`CB001238`), unique across the platform. A generated PPPoE username is the customer number, a second line `CB001238-2`. The cutover import (S11) may instead keep the username a customer already has in the ISP's old system: unique within its tenant only, never in the generated shape, correctable only while the cutover window is open; logins stay tenant-bound through T3.
- **T3** A username authenticates only through routers of its own tenant (`v_radius_auth` joins router tenant to subscription tenant).
- **T4** Tenant secrets are write-only: stored envelope-encrypted, never returned, logged or placed in events. Exception (ADR 0009): PPPoE passwords and router RADIUS secrets are stored readable only through the RADIUS views, because FreeRADIUS must compare them; the application can set and regenerate them but never read them back, and shows a new value once. The one other reader of line passwords is the synthetic login probe (ADR 0019): `isp_worker` reads the probe tenant's, and no other tenant's, through `v_radius_probe_credential_v1`, whose tenant id is fixed in its definition.

## Subscriptions (docs/04)

- **S1** Only `PENDING`, `ACTIVE`, `PAUSED`, `SUSPENDED`, `TERMINATED` are stored. `EXPIRING`, `GRACE`, `RESTRICTED`, `THROTTLED`, `CAPPED`, the access profile and the access reason (`EXPIRED`, `PAUSED`, `GRACE_THROTTLE`, `FAIR_USE`, `DATA_CAP`) are derived from dates and cycle usage at read time, never persisted. A line never shows `ACTIVE` while it is throttled or restricted.
- **S2** Access profile logic, in this order: `PENDING`/`SUSPENDED`/`TERMINATED` → `REJECT`; `PAUSED` → `RESTRICTED`; before the end of grace, cycle usage at or past the package's `data_cap_bytes` → `RESTRICTED`, at or past its `fup_bytes` → `THROTTLED` (N9); `now < expires_at` → `FULL`; `now < expires_at + grace_days` → `FULL` or `THROTTLED` by tenant `grace_mode`; otherwise `RESTRICTED`.
- **S3** The same logic exists in the SQL function `subscription_access_v2()` (used by the `v_radius_auth` view, and through `subscription_access_v3()` and `subscription_line_access()` by the staff list view `v_subscription_status`) and in `AccessPolicy` / `DisplayStatus` / `AccessReason`; a test proves they agree for the same inputs, cycle usage included, on profile, next change, display status and access reason. Change both or neither.
- **S4** Every accepted login carries `Session-Timeout` = seconds until the next profile change, capped at 86400.
- **S5** A renewal extends from the current `expires_at` when the line is not yet past grace, and from the moment of payment when it is. The clock time of `expires_at` is preserved.
- **S6** `grace_days` and `grace_mode` are copied onto the subscription at each renewal; a policy change never alters a cycle already paid for (a line renewed before the mode was kept follows the tenant's).
- **S7** Pause freezes the clock: on resume `expires_at += now − paused_at`. Only an `ACTIVE` line with time remaining can be paused, for at most the tenant's `max_pause_days`; a paused line is `RESTRICTED`, not rejected. At `pause_until` the line resumes by itself: the RADIUS view and `AccessPolicy` treat it as `ACTIVE` with `expires_at + (pause_until − paused_at)` from that instant (E4); the job only records it, with cause `AUTO_RESUME_RECORDED`, which needs no CoA.
- **S8** Lifting a suspension re-evaluates dates; a line that expired during the hold returns as `RESTRICTED`.
- **S9** Every state transition writes an audit row and publishes `SubscriptionStateChanged`. A `PENDING` line that was never paid may be cancelled before activation (`PENDING` → `TERMINATED`, `customers.write`, reason required): its credential is revoked and the wallet is untouched. No line is ever cancelled automatically.
- **S10** Downgrades always apply at the next renewal; immediate upgrades charge proration and change speed by CoA.
- **S11** The cutover import (ADR 0014; cause IMPORTED, S12) activates a line without a payment only: with `paid_until` at most one package duration ahead (a past date is allowed: the line is `ACTIVE` and expired, so the customer gets the pay page), while the tenant's cutover window opened by the Corebalt console is open (at most 30 days), by a holder of `subscriptions.import_active` (the Owner, 2FA), after a preview of that very file. It writes no ledger entry, an audit row per line and one per batch with the preview's totals, and `SubscriptionStateChanged` with cause `IMPORTED`, which no consumer acts on. The cycle for fair use starts one package duration before `paid_until` (N9); grace is copied as at a renewal (S6).
- **S12** A line leaves `PENDING` for `ACTIVE` only by one of these causes: (1) `RENEWAL`, a ledger-backed renewal (S5); (2) `IMPORTED`, the cutover import within an open cutover window (S11); (3) `PROBE`, the platform-only setup of the internal probe tenant (ADR 0019). No other path may activate a line. The cause is stored on the line and recorded in its audit row and `SubscriptionStateChanged`; the database refuses an activation without one, a change to one, and `PROBE` on any tenant but the probe tenant. Returning an activated line to `ACTIVE` (resume, S7; lifting a suspension, S8) is not an activation.

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
- **P3** Matching order: exact account reference → exact match on a customer's old-system reference (`legacy_account_ref`, kept from a cutover, compared trimmed and upper-cased, unique per tenant) → normalised reference → the payer's number, only when it arrives plain or SHA-256-hashed and belongs to exactly one customer → `UNMATCHED` (suspense queue). The old reference comes before normalisation because a digits-only old reference would otherwise read as somebody else's short customer number. A masked number (`2547 ***** 126`) is never matched automatically; staff see a shortlist of customers whose number fits it and assign with an audited click. Never a fuzzy name match.
- **P4** `PaymentReceived` is written to the outbox in the same transaction as the payment record. Billing alone credits the ledger from it (one `PAYMENT` entry per payment, enforced by a unique ledger reference) and issues the receipt; a matched payment with no ledger entry after 5 minutes raises an alert. Manual payments take the same road.
- **P5** A manual payment needs the `payments.record_manual` permission, a reference and an audit row.
- **P6** An STK request with no callback after 2 minutes is queried; after 5 minutes it is `TIMED_OUT` and never silently retried. A provider connection failure is not permission to replay an STK POST: ADR 0022 replaces a failed connection pool only for a subsequent caller request, preserving in-flight work and the valid OAuth token.

## Network (docs/06)

- **N1** Routers are configured by generated onboarding scripts; no customer is ever written into router configuration.
- **N2** FreeRADIUS reads only `radius_client` and `v_radius_auth`, and writes only `radacct`.
- **N3** Expired users are accepted into `RESTRICTED`; `Access-Reject` only for `REJECT` profiles.
- **N4** `Mikrotik-Rate-Limit` is `upload/download` (router rx/tx), e.g. `5M/10M`.
- **N5** CoA is used for speed changes in place; Disconnect for changes of pool or address list. Three retries with backoff, then an alert.
- **N6** All router management (RADIUS, CoA, REST, SNMP) travels only over WireGuard; no router port is exposed publicly.
- **N7** `radacct` is partitioned monthly, kept 13 months; `network_usage_daily` is the only usage source for screens and reports.
- **N8** Device limits are enforced only on hotspot (`shared_users`); PPPoE cannot see devices behind the customer's router.
- **N9** Fair use and data caps count download plus upload in the line's current cycle, from its last renewal (`cycle_started_at`, Q12 default), from the usage roll-up only (N7); only a renewal starts a new cycle (Q13 default). Past `fup_bytes` the line is `THROTTLED`, past `data_cap_bytes` `RESTRICTED` (Q14 default), until it renews; a line whose cycle start is unknown is not metered. A crossing is announced once per line, cycle and kind (`UsageThresholdReached`): Disconnect, then one SMS (Q15 default).
- **N10** A router leaves the platform only by retirement, which is final (`network.write`, a reason, an `Idempotency-Key`): its lines that are not terminated move to a replacement router, required while there are any; its RADIUS secret and REST login are deleted and its open sessions closed in the same transaction; the host's sync removes its WireGuard peer and reloads FreeRADIUS within a minute, so the device can no longer reach the platform (N6). The row stays as history; its name may be reused by its replacement, its tunnel address never.

## Notifications (docs/04)

- **M1** Every notification has a `dedupe_key`, unique per tenant, and the same key is never queued twice: reminders use event, subscription and `expires_at`; the payment message uses the payment ID (one SMS per payment); the fair-use and data-cap messages use kind, subscription and `cycle_started_at` (one per cycle); the "we've moved" notice after a cutover import uses the import batch and the customer (one per customer and batch).
- **M2** Reminders respect the tenant sending window (default 07:00 to 21:00, tenant time zone); the payment message (including its restored variant), the unrecognised-reference reply, package-change messages and the fair-use and data-cap messages (the service changed now) go out immediately.
- **M3** Messages never include secrets, PINs or full national IDs: templates, including a tenant's own wording, may use only a fixed list of placeholders that carry none of them. Two fixed, non-editable messages are the exceptions: the portal sign-in code, and a staff member's temporary password when staff choose "send by SMS" (Q11). Both are sent at once to the person's own phone, never retried, and logged with the secret masked.

- **M4** SMS uses the tenant's Celcom Africa account (ADR 0020). Its key is envelope-encrypted separately from retired provider keys. A send acknowledgement means SENT, never DELIVERED; DELIVERED requires the matching message ID and the live-verified 32/DeliveredToTerminal lookup response; unknown codes remain unconfirmed. Old callback routes cannot confirm delivery of Celcom messages.

## Events and jobs (docs/02)

- **E1** Business change and outbox row commit in one transaction; the relay publishes afterwards.
- **E2** Consumers are idempotent by event UUID and set the tenant context from the event before touching data.
- **E3** Events are past tense, `<Aggregate><Verb>`, versioned, and carry IDs rather than entities.
- **E4** Access to the internet never depends on a scheduled job or the broker; both only speed things up. One bounded exception: renewing a prepaid line from credit already in the wallet happens in the hourly job up to `min(24 h, package duration / 4)` before expiry, so only a job outage longer than that window can restrict a customer whose wallet holds the price.

## Identity and audit (docs/08)

- **A1** Every money movement, access change, secret change and impersonation writes an append-only audit row with actor, before and after.
- **A2** Finance and admin roles require 2FA; permissions are fixed codes checked server-side, never only in the UI. `settings.write` (the account's own settings) is held by the built-in Owner role only.
- **A3** Logs mask phone numbers and never contain secrets, passwords or PINs.
