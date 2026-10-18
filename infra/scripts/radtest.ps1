# Lab RADIUS login test inside the FreeRADIUS container (expects Access-Reject at step 1).
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    docker compose exec freeradius corebalt-radtest @args
} finally {
    Pop-Location
}
exit $LASTEXITCODE
