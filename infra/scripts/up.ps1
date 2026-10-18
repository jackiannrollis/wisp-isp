# Build and start the local stack (PostgreSQL, RabbitMQ, Redis, backend, FreeRADIUS, Caddy).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (-not (Test-Path ".env")) { throw "infra/.env missing: copy infra/.env.example and set passwords" }
docker compose up -d --build --wait @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
docker compose ps
