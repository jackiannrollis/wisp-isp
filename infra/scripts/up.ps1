# Build and start the local stack (PostgreSQL, RabbitMQ, Redis, backend, FreeRADIUS, Caddy). It also
# removes the e2e stack's fake Daraja and points the backend at the real Daraja sandbox again (ADR 0012).
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    if (-not (Test-Path ".env")) { throw "infra/.env missing: copy infra/.env.example and set passwords" }
    docker compose up -d --build --wait --remove-orphans @args
    if ($LASTEXITCODE -ne 0) { return }
    docker compose ps
} finally {
    Pop-Location
}
exit $LASTEXITCODE
