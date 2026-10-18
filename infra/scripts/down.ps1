# Stop the local stack. Pass -v to also delete the database and broker volumes.
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    docker compose down @args
} finally {
    Pop-Location
}
exit $LASTEXITCODE
