# Corebalt ISP Platform

@context/project.md
@context/domain-rules.md
@context/conventions.md

Load on demand: `context/glossary.md` (terms), `context/decisions.md` (what is already decided),
`context/open-questions.md` (pending decisions: use the stated default and flag it in the PR, never
invent an answer). Long-form docs and ADRs are in `docs/`.

## Current phase

Step 1 of the v1 build order (docs/09) is done: the foundation. No business features exist yet.
Build the next steps in order; each should end with something demonstrable.

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
connects as the non-owner role `isp_app` so row-level security is really exercised.

### Frontend (`frontend/`, one Angular workspace with `admin` and `portal`)

| Task | Command |
|---|---|
| Install | `npm ci` |
| Dev servers (proxy `/api` to :8080) | `npm run start:admin` (4200), `npm run start:portal` (4201) |
| Lint, format check | `npm run lint`, `npm run format:check` (fix with `npm run format`) |
| Build both apps | `npm run build` |
| Test both apps (Vitest, jsdom, no browser) | `npm run test` |
| Regenerate API types from the running backend | `npm run api:generate` (env `API_DOCS_URL` overrides the URL) |

### Local stack (`infra/`)

First time: copy `infra/.env.example` to `infra/.env` and change the passwords.

| Task | Windows (PowerShell) | Linux/macOS |
|---|---|---|
| Start everything (builds images, waits for health) | `infra\scripts\up.ps1` | `infra/scripts/up.sh` |
| Stop (add `-v` to drop data) | `infra\scripts\down.ps1` | `infra/scripts/down.sh` |
| Follow logs | `infra\scripts\logs.ps1 backend` | `infra/scripts/logs.sh backend` |
| Run migrations only | `infra\scripts\migrate.ps1` | `infra/scripts/migrate.sh` |
| RADIUS lab login (Access-Reject expected until step 3) | `infra\scripts\radtest.ps1` | `infra/scripts/radtest.sh` |
| All checks (what CI runs) | `infra\scripts\test.ps1` | `infra/scripts/test.sh` |

Services: API :8080, Caddy :8088, PostgreSQL :5432, RabbitMQ :5672 (UI :15672), Redis :6379,
FreeRADIUS :1812/:1813 UDP (ports configurable in `.env`).

## Where code goes

- One module per package under `backend/src/main/java/com/corebalt/isp/<module>/`, with `api/`,
  `domain/`, `application/`, `infrastructure/`. Other modules may use only `<module>.api`; the
  allowed dependencies are declared in each module's `package-info.java` and `ModularityTest`
  enforces them.
- `com.corebalt.isp.shared` is the shared kernel (OPEN module): `Money`, `TenantContext`,
  `TenantIterator`, `UuidV7`, the outbox (`OutboxPublisher`, `OutboxRelay`, `IdempotentConsumer`),
  problem details and security. No business rules go there.
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
