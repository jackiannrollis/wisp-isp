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
| Q11 | Step 8 asked for an optional "send by SMS" of a staff member's temporary password; M3 says messages never include secrets. Allow it (as an M3 exception), or not? | Not built: the password is shown once to the staff member who invited, who passes it on | Product, security | Before the admin invite screen ships |

## Resolved

| Date | Question | Answer | Where it now lives |
|---|---|---|---|
| 2026-10-17 | Frontend framework: Next.js or Angular? | Angular 22 | [ADR 0002](../docs/adr/0002-angular-for-web-apps.md) |
| 2026-10-17 | Message broker from day one or Postgres-backed jobs first? | RabbitMQ from day one with a transactional outbox | [ADR 0003](../docs/adr/0003-rabbitmq-with-transactional-outbox.md) |
