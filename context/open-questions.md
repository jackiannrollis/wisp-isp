# Open questions

Decisions still pending. Do not invent an answer in code; pick the stated default where one exists and flag it in the pull request.

| # | Question | Default until decided | Owner | Needed by |
|---|---|---|---|---|
| Q1 | Pricing for tenant ISPs: per active subscriber per month, or flat tiers? | Per active subscriber, metered monthly from dashboard counts | Corebalt | Before the first paying tenant |
| Q2 | Which Nairobi data centre hosts RADIUS node B, the replica and hub B? | Any Tier III facility with Hetzner-reachable peering; evaluate at least two quotes | Corebalt | Before Gate 1 |
| Q3 | Payments only into each tenant's own Paybill, or also a Corebalt-collected option (Trilho) with settlement to tenants? | Tenant's own Paybill only | Corebalt | v2 planning |
| Q4 | Default grace and partial-payment policy for new tenants | Prepaid: 0 grace days, `WAIT_FOR_FULL`; postpaid: 2 grace days | Product | v1 |
| Q5 | Pilot ISP: who, how many subscribers, start date | — | Corebalt | Before step 9 of the v1 build order |
| Q6 | Expiry clock: exact timestamp (default) or end of day in the tenant's time zone? | Exact timestamp, clock time preserved across renewals | Product | v1 |
| Q7 | Customer portal login: phone + PIN, SMS OTP, or both? | Both: PIN as the quick path, OTP for reset | Product | v1 |
| Q8 | Public marketing site: Angular SSR or a static generator? | Static generator; separate repository | Corebalt | After Gate 1 |
| Q9 | Retention of `radacct` detail beyond 13 months for lawful requests | 13 months of detail, roll-ups kept 7 years | Legal | Before ODPC registration |
| Q10 | Is C2B validation (reject unknown references before charge) available on the pilot's shortcode? | Assume not; rely on the suspense queue | Pilot ISP | Daraja go-live |
| Q12 | Fair use: which period does the allowance count? | The line's current cycle: from its last renewal to `expires_at` | Product | docs/09 step 8b |
| Q13 | Fair use: what resets the allowance? | Only a renewal (a new cycle); a top-up that does not renew, a pause or a package change does not | Product | docs/09 step 8b |
| Q14 | Hard data cap (`data_cap_bytes`): what happens when it is reached? | RESTRICTED (walled garden with the pay page) until the next renewal; never rejected (N3) | Product | docs/09 step 8b |
| Q15 | Fair-use messages: when does the customer hear? | One SMS when the allowance is passed (dedupe per line and cycle, M1); no warning before it | Product | docs/09 step 8b |
| Q17 | How does an impersonation started in the platform console reach the admin app ([ADR 0017](../docs/adr/0017-impersonation-hand-off.md), proposed)? | API-only: the console lists and ends impersonations; one is started and used with `curl` | Corebalt | Before Corebalt support works inside a pilot's admin app |

## Resolved

| Date | Question | Answer | Where it now lives |
|---|---|---|---|
| 2026-10-26 | Q16: may the CSV import activate a line with the expiry the customer already paid for, without a payment? | Yes, as rule S11: only while a cutover window opened by the Corebalt console is open (at most 30 days), by the Owner's own permission `subscriptions.import_active` (2FA), after a preview of the file (totals, the value of the time granted, warnings for swapped date formats), with `paid_until` at most one package duration ahead and date-only values read at a chosen time of day in the tenant's zone; no ledger entry, cause `IMPORTED`. The same import keeps old PPPoE credentials (T2 widened) and old M-Pesa references (P3) | [ADR 0014](../docs/adr/0014-cutover-import-with-current-expiry.md); context/domain-rules.md S11, T2, P3; `infra/ops/runbooks/pilot-onboarding.md` step 6 |
| 2026-10-20 | Q11: optional "send by SMS" of a staff member's temporary password, against M3? | Yes, as an explicit M3 exception: fixed wording, to the staff member's own phone, 72-hour expiry, forced change at first sign-in, audit row, logged masked | context/domain-rules.md M3; `TemporaryPasswordSms` |
| 2026-10-17 | Frontend framework: Next.js or Angular? | Angular 22 | [ADR 0002](../docs/adr/0002-angular-for-web-apps.md) |
| 2026-10-17 | Message broker from day one or Postgres-backed jobs first? | RabbitMQ from day one with a transactional outbox | [ADR 0003](../docs/adr/0003-rabbitmq-with-transactional-outbox.md) |
