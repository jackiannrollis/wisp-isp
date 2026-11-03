# Corebalt ISP Platform

Multi-tenant SaaS for Kenyan ISPs, WISPs and Wi-Fi vendors: customers, packages, subscriptions,
wallet billing, M-Pesa payments and router control in one platform, where **billing state is
network entitlement**. Paid means online, expired past grace means a walled garden with a pay
page, and a payment restores access within seconds with no staff involved.

| Part | Stack |
|---|---|
| `backend/` | Java 25, Spring Boot 4.1, Spring Modulith, PostgreSQL 17 with row-level security, RabbitMQ with a transactional outbox |
| `frontend/` | Angular 22: ISP `admin`, customer `portal` and platform `console` |
| `radius/` | FreeRADIUS 3.2 reading PostgreSQL views (`radius_client`, `v_radius_auth`) |
| `infra/` | Docker Compose for local development, Caddy, scripts |
| `docs/`, `context/` | Product and architecture documentation, ADRs, domain rules and conventions |

Status: the v1 admin, portal and platform console are implemented; production preparation
(step 9) and live provider validation remain. The MikroTik CHR lab now verifies network profiles,
CoA/Disconnect, Session-Timeout and automatic restoration after a fake cash payment. Celcom SMS
delivery, a Daraja C2B sandbox payment through wallet credit/receipt/renewal, and a successful
KES 1 sandbox STK payment through callback/credit/receipt are verified. Real-host deployment
and the Nairobi failover node are still pending. The combined Daraja sandbox callback-to-CHR test
restored FULL access in 4.357 seconds, with one credit/renewal/receipt and no manual reconnect.
See `infra/lab/daraja-restore-validation-2026-11-02.md` for the restoration proof and
`infra/lab/daraja-state-validation-2026-11-03.md` for confirmed connection recovery. See
`CLAUDE.md` for the current handoff and `docs/09-roadmap.md` for the pilot acceptance gate.

## Run it locally

Requires JDK 25 or newer (point `JAVA_HOME` at it), Node 24 and Docker Desktop.

```powershell
Copy-Item infra\.env.example infra\.env   # then edit the passwords
infra\scripts\up.ps1                      # PostgreSQL, RabbitMQ, Redis, API, FreeRADIUS, Caddy
cd frontend; npm ci; npm run start:admin  # http://localhost:4200
```

On Linux or macOS use `cp` and `infra/scripts/up.sh`. Check the API at
`http://localhost:8080/actuator/health` and the docs at `http://localhost:8080/swagger-ui.html`.

Run every check with `infra\scripts\test.ps1` (or `test.sh`). `CLAUDE.md` lists all build, test and
run commands and the review rules.

For browser regression, run `infra\e2e\up.ps1 -Fresh -Seed` (Linux/macOS:
`sh infra/e2e/up.sh --fresh --seed`), then `cd frontend` and `npm run e2e`.
This resets only the disposable `corebalt-e2e` fixtures. Admin, portal and console run on
ports 4300/4301/4302 against fake payment/SMS providers; working app accounts and credentials
are preserved. Console bootstrap requires fresh test fixtures for each complete run.
