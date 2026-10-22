# Corebalt ISP Platform

@context/project.md
@context/domain-rules.md
@context/conventions.md

Load on demand: `context/glossary.md` (terms), `context/decisions.md` (what is already decided),
`context/open-questions.md` (pending decisions: use the stated default and flag it in the PR, never
invent an answer). Long-form docs and ADRs are in `docs/`.

## Current phase

Steps 1 and 3 to 7 of the v1 build order (docs/09) are done; step 2, the network lab, is done
except its Hyper-V half (`infra/lab/scripts/lab.ps1`), which has not run yet, so CoA/Disconnect,
the restricted-mode rules and Session-Timeout on a real router are unproven (status note in
`infra/lab/README.md`). FreeRADIUS reads `radius_client_v3` and `v_radius_auth_v7`; `AccessPolicy`
agrees with the view under test (S3), fair use and data caps included (step 8b, N9); credentials follow ADR 0009. Money: M-Pesa callbacks, manual
payments, suspense and reconciliation (payments); ledger and receipts (billing); renewals and
`CreditApplied` (subscriptions). Notifications: SMS through each tenant's Africa's Talking account,
one payment message per payment, reminders in the sending window, templates with part counts.
Daraja and Africa's Talking are verified against fakes only; their sandbox scripts have not run.
Step 8 (dashboard and portal) is in progress. Done: sign-in and tokens (ADR 0010: staff, portal,
platform admins, impersonation), RADIUS accounting into partitioned `radacct` with the usage roll-up,
and the tunnel worker (ADR 0011) polling routers over RouterOS REST and sending CoA/Disconnect
(N5; proven against a fake router and radclient, not yet on a real router). The dashboard and portal
APIs are done too (`/api/v1/dashboard`; `/api/v1/portal/...` per module), and every v1 screen of the
admin Angular app. Step 8b, fair use and data caps, is done (N9: cycle usage in `network_line_usage`,
`UsageThresholdReached` with Disconnect and one SMS; package limits and usage against the allowance on
screen). The portal Angular app is done: PIN sign-in with an SMS code to set or reset the PIN (Q7
default), lines with status and usage, Pay Now per line and pay-all by STK with the restore wait and
the 90-second router fallback, payments, receipts and the wallet statement. Its SMS-code sign-in and a
live STK payment are covered by tests only (no Africa's Talking or Daraja account locally). The
Playwright suite (ADR 0012: sign-in, customer search, Pay Now through a fake Daraja to "Internet
restored") runs against the real stack in its own CI job.
Step 9 (production) is in progress; nothing has run on a real host yet. Done: cancel before
activation (S9); release images to private GHCR by commit SHA, signed once the cosign secrets exist;
containerised Ansible (`infra/ops`) with host hardening, the LUKS data disk and the WireGuard hub,
converged in CI on a throwaway Debian; the staging/production compose stack with the web front
(wildcard certificate by DNS-01, both apps, `pay.<domain>`), tested locally and in CI
(`infra/stack/test.sh`); deploys by digest with signature checks, one service at a time behind
health gates, rollback on failure (`ops deploy`, `ops rollback`, `stackctl`). Not yet: pgBackRest
backups and the restore drill, monitoring and alerts, the key-escrow, failover-drill and onboarding
runbooks, the Nairobi node (pending Q2).

## Commands

Prerequisites: JDK 25 or newer (set `JAVA_HOME`; Maven uses it, not `java` on PATH). The code targets
Java 25 (`--release 25`); CI and the Docker image build on JDK 25, which stays the reference.
Also Node 24 with npm 11 and Docker with Compose v2.
On Windows run the `.ps1` scripts from PowerShell; on Linux or macOS use the matching `.sh` files.

### Backend (`backend/`, Maven wrapper)

| Task | Command |
|---|---|
| Build and run every test (unit, Modulith verification, Testcontainers) | `./mvnw verify` (Windows: `.\mvnw.cmd verify`) |
| Unit tests only (no Docker) | `./mvnw test` |
| One integration test | `./mvnw verify -Dit.test=OutboxIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` |
| Run on the host against the compose stack (stop the `backend` service first) | PowerShell: `$env:SPRING_PROFILES_ACTIVE='local'; $env:ISP_DB_APP_PASSWORD='…'; $env:ISP_DB_OWNER_PASSWORD='…'; $env:ISP_RABBITMQ_PASSWORD='…'; .\mvnw.cmd spring-boot:run` (bash: the same variables, then `./mvnw spring-boot:run`) |
| OpenAPI | `http://localhost:8080/api/docs` (Swagger UI at `/swagger-ui.html`) |

Unit tests are `*Test`, Testcontainers tests are `*IT` and extend `support/IntegrationTest`, which
connects as the non-owner role `isp_app` so row-level security is really exercised. Tests call the API
with real RS256 tokens signed by a key generated per test JVM (`support/TestTokens`, `support/Api`).
After `infra/lab/seed.sql` the local stack has a lab admin: tenant `CB`,
`lab-admin@corebalt.example.test`, password `lab-admin-password` (fake, local only), and customer
`CB000001` can sign into the portal with PIN `4071` (fake, local only).

### Frontend (`frontend/`, one Angular workspace with `admin` and `portal`)

| Task | Command |
|---|---|
| Install | `npm ci` |
| Dev servers (proxy `/api` to :8080) | `npm run start:admin` (4200), `npm run start:portal` (4201) |
| Lint, format check | `npm run lint`, `npm run format:check` (fix with `npm run format`) |
| Build both apps | `npm run build` |
| Test both apps (Vitest, jsdom, no browser) | `npm run test` |
| Regenerate API types from the running backend | `npm run api:generate` (env `API_DOCS_URL` overrides the URL) |
| End-to-end suite (Playwright, installed Chrome; start the e2e stack first, see below) | `npm run e2e` (type-check: `npm run e2e:typecheck`) |

### Local stack (`infra/`)

First time: copy `infra/.env.example` to `infra/.env`, change the passwords and create the token
signing key with `infra\scripts\jwt-key.ps1` (`infra/scripts/jwt-key.sh`).

| Task | Windows (PowerShell) | Linux/macOS |
|---|---|---|
| Start everything (builds images, waits for health) | `infra\scripts\up.ps1` | `infra/scripts/up.sh` |
| Stop (add `-v` to drop data) | `infra\scripts\down.ps1` | `infra/scripts/down.sh` |
| Follow logs | `infra\scripts\logs.ps1 backend` | `infra/scripts/logs.sh backend` |
| Run migrations only | `infra\scripts\migrate.ps1` | `infra/scripts/migrate.sh` |
| Load the network-lab fixtures (restarts FreeRADIUS) | `infra\lab\scripts\seed.ps1` | `infra/lab/scripts/seed.sh` |
| RADIUS lab login, e.g. `CB000004` (after seeding) | `infra\scripts\radtest.ps1 CB000004` | `infra/scripts/radtest.sh CB000004` |
| Daraja sandbox round-trip (needs `DARAJA_SANDBOX_*` and an https `CALLBACK_BASE_URL` in `.env`; skips otherwise) | `infra\scripts\daraja-sandbox.ps1` | `infra/scripts/daraja-sandbox.sh` |
| Africa's Talking sandbox round-trip (needs `AT_SANDBOX_*` and an https `CALLBACK_BASE_URL`; skips otherwise) | `infra\scripts\at-sandbox.ps1` | `infra/scripts/at-sandbox.sh` |
| Create or rotate the JWT signing key in `.env` (ADR 0010) | `infra\scripts\jwt-key.ps1 [-Rotate]` | `infra/scripts/jwt-key.sh [--rotate]` |
| Break-glass reset of a platform admin (prints a temporary password once) | `infra\scripts\reset-platform-admin.ps1 <email>` | `infra/scripts/reset-platform-admin.sh <email>` |
| Stack for the end-to-end suite: the backend on the fake Daraja (ADR 0012); the seed flag loads the lab fixtures. Run `up` afterwards to restore the real sandbox | `infra\e2e\up.ps1 [-Seed]` | `infra/e2e/up.sh [--seed]` |
| Staging and production hosts (Ansible, cosign, crane in a container; runbook `infra/ops/README.md`) | `infra\ops\ops.ps1 lint\|secrets\|bootstrap\|provision\|init-disk\|unlock\|deploy\|rollback <inventory> [-Limit host] [-Release sha]` | `infra/ops/ops.sh …` |
| Test the staging/production compose stack locally (local TLS, lab seed, routing checks) | `bash infra/stack/test.sh` (Git Bash); `… down` removes it | `infra/stack/test.sh` |
| Test the playbooks against a throwaway Debian 13 container | `bash infra/ansible/tests/converge.sh` (Git Bash) | `infra/ansible/tests/converge.sh` |
| All checks (what CI runs, except the end-to-end suite) | `infra\scripts\test.ps1` | `infra/scripts/test.sh` |

Services: API :8080, Caddy :8088 (lab pay page :8089), PostgreSQL :5432, RabbitMQ :5672
(UI :15672), Redis :6379, WireGuard hub :51820 UDP, FreeRADIUS :1812/:1813 UDP (ports configurable
in `.env`). FreeRADIUS runs in the hub's network namespace, so routers are matched by tunnel address.

Network lab with two MikroTik CHR VMs in Hyper-V (Windows only): `infra\lab\scripts\lab.ps1`
(`create`, `onboard`, `check`, `coa`, `disconnect`, ...); runbook in `infra/lab/README.md`.

## Where code goes

- One module per package under `backend/src/main/java/com/corebalt/isp/<module>/`, with `api/`,
  `domain/`, `application/`, `infrastructure/`. Other modules may use only `<module>.api`; the
  allowed dependencies are declared in each module's `package-info.java` and `ModularityTest`
  enforces them.
- `com.corebalt.isp.shared` is the shared kernel (OPEN module): `Money`, `TenantContext`,
  `TenantIterator`, `UuidV7`, the outbox (`OutboxPublisher`, `OutboxRelay`, `IdempotentConsumer`,
  `EventQueues`), the audit trail interface (`AuditTrail`, implemented by identity), idempotency keys,
  problem details and security. No business rules go there. Tenant secrets go through
  `tenancy.api.SecretStore` (envelope encryption, master key `ISP_MASTER_KEY`).
- Migrations: `backend/src/main/resources/db/migration/V<yyyyMMddHHmm>__<module>_<description>.sql`.
- FreeRADIUS configuration: `radius/`; views it reads are versioned, never altered in place.

## Rules a reviewer checks

- **Tenant scoping (T1-T4).** Every tenant-scoped table has `tenant_id`, an RLS policy using
  `nullif(current_setting('app.tenant_id', true), '')::uuid`, and grants to `isp_app` only for what
  it needs. Code takes the tenant from `TenantContext`, never from request input. Cross-tenant work
  goes through `TenantIterator`, never by skipping the context.
- **Money (B2, B6).** Every amount is a `Money` (long minor units + currency); arithmetic and
  rounding only through it; no `double`, `float` or `BigDecimal` arithmetic on amounts elsewhere.
- **Outbox for events (E1-E3).** Events are records in the owning module's `api` package,
  implementing `DomainEvent`, published only with `OutboxPublisher` inside the business
  transaction. Consumers go through `IdempotentConsumer`.
- **Idempotency keys.** API writes that move money or change access require an `Idempotency-Key`
  header and return the original result on replay; M-Pesa callbacks are idempotent on
  (`tenant_id`, `TransID`) (P1).
- **No secrets in logs or fixtures (T4, A3).** Secrets only through `SecretStore`; never in
  properties, logs, events, problem details or test fixtures (use obvious fakes); phone numbers
  masked in logs.
- Append-only tables (`*_ledger_entry`, `identity_audit_log`) get no UPDATE/DELETE grants.
- A PR that changes a business rule updates `docs/` and `context/domain-rules.md`; a new technical
  decision adds an ADR and a row in `context/decisions.md`.
- Conventional commits; cite domain-rule IDs or ADRs in commit bodies and test names
  (`S5_renewalExtendsFromCurrentExpiry`).
