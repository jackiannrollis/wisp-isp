# ADR 0014 · Cutover import: an imported line activated with the expiry the customer already paid for

**Status:** Proposed (open question Q16; nothing of this is built)
**Date:** 2026-10-26

## Context

A line is `PENDING` until its first renewal is paid (docs/04; S5 renews from the moment of payment when a line is past grace; B5). The CSV import of a tenant's customers (docs/07 step 4) opens `PENDING` lines only. An ISP moving existing, paying customers onto the platform therefore has two ways to cut over (`infra/ops/runbooks/pilot-onboarding.md`, Customers): per customer on their renewal date, which means a rolling month with the old system running beside the platform, or on one day with a manual payment per customer, which gives every customer a fresh cycle and gives away the days they had already paid for in the old system.

What exists: `activated_at`, `cycle_started_at`, `grace_days` and `grace_mode` on the line; `SubscriptionStateChanged` with the causes `STAFF_ACTION`, `RENEWAL` and `AUTO_RESUME_RECORDED`; an import that validates every row or none; the audit trail (A1).

## Options

**A. The import may activate a line with its current expiry (recommended).** A CSV column `paid_until` (ISO 8601, optional per row). A row that has it opens the line `ACTIVE` at import: `expires_at` = `paid_until`, `activated_at` = now, `cycle_started_at` = `paid_until` minus the package duration (so fair use counts the right cycle, N9), grace days and mode copied from the tenant's policy (S6). No ledger entry: the old system collected that money, and the wallet starts empty. An audit row `subscriptions.activated_by_import` with the value, and `SubscriptionStateChanged` (`PENDING` to `ACTIVE`) with a new cause `IMPORTED`, which no consumer acts on: there is no session to change. Guards:

- Only at import, never on an existing line, and only for a line the same file creates.
- `paid_until` at most one package duration after the import: a cutover grants at most the cycle the customer already paid for. A `paid_until` in the past is allowed: the line is `ACTIVE` and expired, so the customer lands on the pay page (`RESTRICTED`, S2) instead of being rejected, which is the right experience for someone whose old service had lapsed.
- The column is accepted only when the importer also holds `payments.record_manual` (a 2FA permission, A2): declaring a line paid by hand is the same kind of power as recording a payment by hand (P5). Without it the file is rejected with a row error, like any other validation.
- The import result says which rows were activated; no payment SMS goes out (there was no payment); reminders run as for any line.

**B. Keep the rule (the default while Q16 is open).** The runbook's two cutovers stay as they are.

**C. A "cutover payment".** A manual payment flagged as settling the old cycle, activating until a given date instead of one duration ahead. Rejected: it records a money movement that did not happen on the platform (B1, B3), numbers a receipt for money the old system took (B7) and bends P4 and P5 for a one-time migration.

## What option A changes, if accepted

- A rule, proposed as **S11**: "A line is activated without a payment only by the onboarding import, with `paid_until` at most one package duration ahead and a role that may record manual payments; it writes no ledger entry, an audit row and `SubscriptionStateChanged` with cause `IMPORTED`." docs/04's state diagram gains "or the cutover import" on `PENDING` to `ACTIVE`, and the import's description the column.
- Code: the import parses and validates the column (format, the cap against the row's package, the permission); `LifecycleService` gets the activation by import; the event cause; `CustomerImportResultDto` rows say `activatedUntil`. No migration: the columns exist.
- Tests, citing S11: an import with `paid_until` makes the line `ACTIVE` with the given expiry and cycle start, writes the audit row and the event, no ledger entry, and the RADIUS view answers `FULL` for it (S3 needs no change: an `ACTIVE` line with dates is already covered); the cap and the permission reject the file; an expired `paid_until` gives `RESTRICTED`.
- The runbook's cutover table gains a third row, "on one day, lossless", and the pilot can be cut over in an afternoon.
- Size: about a day, in the subscriptions module only.

## Consequences

- An importer with the power could grant any new customer a free cycle at import time. The cap bounds it to one cycle, the audit row names who did it, and the permission is one that already requires 2FA.
- The platform's own metering (Q1) counts these lines as active subscribers from the import, which is right: they are served.
- The first renewal of an imported line follows S5 from its `paid_until`, so the customer's billing day is preserved across the migration, which is the point.
- Nothing changes for logins: the RADIUS view and `AccessPolicy` read `state` and the dates as before.
