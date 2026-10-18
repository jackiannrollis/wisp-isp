# Apply Flyway migrations without starting the API (the API also migrates on startup).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose up -d --wait postgres
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
docker compose --profile tools run --rm flyway migrate
exit $LASTEXITCODE
