# Apply Flyway migrations without starting the API (the API also migrates on startup).
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    docker compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) { return }
    docker compose --profile tools run --rm flyway migrate
} finally {
    Pop-Location
}
exit $LASTEXITCODE
