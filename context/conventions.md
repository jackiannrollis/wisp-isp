# Engineering conventions

Short rules for writing and reviewing code on this project. When a rule here conflicts with a framework default, this file wins; when it is silent, follow the framework's idiomatic style.

## Repository layout

```
backend/            Spring Boot modular monolith (Maven; switch to Gradle only by team decision)
  src/main/java/com/corebalt/isp/<module>/
    api/            public interface of the module: facade, DTOs, published events
    domain/         entities, value objects, domain services, AccessPolicy-style rules
    application/    command handlers, queries, transactions, outbox writes
    infrastructure/ JPA repositories, external clients (Daraja, SMS, RouterOS, RADIUS CoA)
    package-info.java   @ApplicationModule with allowed dependencies
  src/main/resources/db/migration/   Flyway migrations
frontend/
  admin/            Angular admin dashboard
  portal/           Angular customer portal
  captive/          plain HTML hotspot pages (v2)
radius/             FreeRADIUS configuration, SQL views, test scripts
infra/              Docker Compose, Caddy, Prometheus, Grafana, deployment scripts, RouterOS script templates
docs/               long-form documentation and ADRs
context/            this folder
```

## Backend (Java, Spring Boot)

- **Modules first.** New code goes in exactly one module; cross-module calls go through the module's `api` package (a facade or a published event). The Spring Modulith verification test must stay green.
- **Ports against cycles.** When module A needs module B but B already depends on A's events, A declares a port interface in its api package and B implements it; never add the reverse dependency. Example: `subscriptions.api.LineCredentialStore`, implemented by the network module.
- **Entities and IDs.** JPA entities per module; IDs are UUIDv7 generated in the application; every tenant-scoped entity has `tenantId`, set from `TenantContext`, never from request input.
- **Tenant context.** Resolved once per request or event in a filter or listener; repositories read it from a thread-bound holder; a transaction sets `app.tenant_id` for PostgreSQL RLS. Code that needs all tenants (platform console, schedulers) uses an explicit `TenantIterator`, logged.
- **Money.** `Money` value object wrapping `long minorUnits` and `Currency`; arithmetic through the value object only; rounding rules live there (proration rounds up to the shilling).
- **Time.** `Instant` in code and `timestamptz` in the database; `Clock` injected so tests control time; tenant `ZoneId` only for display and day boundaries.
- **Commands and queries.** One command handler per use case, transactional, writes the outbox row before returning; queries are separate read models and may use the replica.
- **Events.** Java records in the module's `api` package; past-tense names; carry IDs, `tenantId`, `eventId` (UUID), `occurredAt`, `version`. Consumers are `@Transactional` and idempotent by `eventId`.
- **Validation.** Bean Validation on request DTOs at the boundary; domain invariants enforced again in the domain layer (`domain-rules.md` IDs in the exception message).
- **Mapping.** MapStruct for DTO ↔ domain; no entity leaves a module.
- **Errors.** RFC 9457 problem details; stable `type` URIs per error; never leak stack traces or SQL.
- **Migrations.** Flyway, `V<yyyyMMddHHmm>__<module>_<description>.sql`; backwards compatible with the previous release; no data migration mixed with schema changes; views used by FreeRADIUS are versioned (`v_radius_auth_v4`) and switched in FreeRADIUS config, never altered in place.
- **Secrets.** Through `SecretStore` only; never in properties files, logs, events or test fixtures (use fakes).
- **Logging.** Structured JSON via the logging MDC with `tenantId`, `correlationId`, `module`; phone numbers masked; no payloads with secrets.
- **Tests.** Unit tests for domain rules (cite rule IDs in test names: `S5_renewalExtendsFromCurrentExpiry`); Testcontainers for PostgreSQL and RabbitMQ; a FreeRADIUS integration test runs `radtest` against the view; the `AccessPolicy`-versus-view agreement test runs on every migration touching either.
- **Style.** Google Java Style with 4-space indentation; `final` by default; no Lombok on entities (records and explicit code instead); `Optional` for return values only.

## Frontend (Angular)

- **Structure.** `core/` (auth, interceptors, guards, tenant/theme), `shared/` (UI components, pipes, models), `features/<area>/` (customers, subscriptions, billing, payments, network, settings), each feature lazy-loaded.
- **Components.** Standalone components, `OnPush`, Signals for local and store state, `inject()` over constructor injection; templates use the new control flow (`@if`, `@for`).
- **Data access.** One service per API resource under `features/<area>/data/`; typed models generated from the OpenAPI spec; no business rules in the frontend, display `displayStatus` and `accessProfile` as the API returns them.
- **Forms.** Reactive forms with typed controls; validation messages shared; money inputs in shillings, converted to minor units at the API boundary.
- **Permissions.** Route guards and a `*hasPermission` directive driven by the permission codes in the JWT; the server is the authority.
- **Tables.** Server-side pagination, sorting and filtering for every list; never load all customers.
- **Theming.** Tenant logo and colour from the API at startup; CSS variables; light and dark supported.
- **Testing.** Component tests with the Angular testing library style; a small Playwright suite for login, pay-now and customer search.
- **Style.** Angular ESLint and Prettier defaults; 2-space indentation; file names `customer-list.component.ts`.

## API

- Base path `/api/v1`; resources plural and kebab-case (`/customers`, `/ledger-entries`); IDs are UUIDs; tenant never in the path (it comes from the principal or subdomain).
- `GET` lists: `page`, `size` (max 200), `sort`, filter parameters named after fields; responses carry `items`, `page`, `size`, `total`.
- Writes that move money or change access require an `Idempotency-Key` header (1 to 100 characters, unique per tenant); replays return the original result with `Idempotent-Replayed: true`, and the same key with a different request is a 422. `shared.web.IdempotentRequests` stores the response in the business transaction. Responses that carry a secret (show-once passwords) are not idempotent: a replay would reveal the secret again.
- Timestamps in ISO 8601 UTC; money as `{ "amount": 250000, "currency": "KES" }` in minor units.
- Callback endpoints (`/callbacks/mpesa/<tenant token>/...`) are outside `/api/v1`, unauthenticated by JWT, IP-restricted, and respond within one second.
- OpenAPI generated from code and published at `/api/docs`; the frontend's models are generated from it in CI.
- WebSocket topic `/topic/tenant/<id>/dashboard` pushes dashboard counters and router status.

## Database

- Snake_case names; table prefix is the owning module (`billing_ledger_entry`); primary key `id`; foreign keys named `<table>_id`.
- `tenant_id` on every tenant-scoped table with an RLS policy; composite indexes start with `tenant_id`.
- `created_at`, `updated_at` on mutable tables; append-only tables (`ledger_entry`, `audit_log`) have no `updated_at` and no update or delete grants for the application role.
- `outbox_event`: the application role may INSERT, and UPDATE only `published_at`, `attempts` and `last_error` (column-level grants); no DELETE. Published rows older than 7 days are purged by an owner-role maintenance job.
- Encrypted columns are `bytea` with a `_enc` suffix and a `key_version`.
- FreeRADIUS uses its own role with select on two views and insert/update on `radacct` only.
- `network_radius_credential` (ADR 0009): the application role may insert rows and update `secret`, and select only the key columns, never `secret`; only the RADIUS views read it.

## Events and queues (RabbitMQ)

- Exchange `isp.events` (topic); routing key `<module>.<event>.v<version>` (`payments.PaymentReceived.v1`).
- Queue per consumer: `<module>.<consumer>` with a dead-letter queue `<module>.<consumer>.dlq`; quorum queues, persistent messages, prefetch 10.
- Retry three times with exponential backoff (5 s, 30 s, 2 min), then dead-letter; an alert fires on any dead-lettered message.
- Message body is JSON of the event record; headers carry `eventId`, `tenantId`, `correlationId`, `occurredAt`.

## Git and reviews

- Trunk-based: short-lived branches, squash merge, main always deployable.
- Conventional commits (`feat(billing): proration charge on immediate upgrade`); reference domain rule IDs or ADR numbers in the body when relevant.
- A pull request that changes a business rule updates `docs/` and `context/domain-rules.md` in the same PR; a new technical decision adds an ADR.
- CI: build, unit and integration tests, Modulith verification, dependency and image scans, OpenAPI diff posted on the PR.
- Reviewers check: tenant scoping, money arithmetic through `Money`, outbox used for events, idempotency keys on money and access writes, no secrets in logs or fixtures.
