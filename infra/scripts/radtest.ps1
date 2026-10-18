# Lab RADIUS login test inside the FreeRADIUS container (expects Access-Reject at step 1).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose exec freeradius corebalt-radtest @args
exit $LASTEXITCODE
