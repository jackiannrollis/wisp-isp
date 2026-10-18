# Follow logs, optionally for given services: .\logs.ps1 backend freeradius
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    docker compose logs -f --tail=200 @args
} finally {
    Pop-Location
}
exit $LASTEXITCODE
