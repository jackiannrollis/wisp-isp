# Lab RADIUS login inside the FreeRADIUS container: radtest.ps1 [username] [password].
# Load the lab fixtures first (infra/lab/scripts/seed.ps1); see radius/scripts/radtest.sh.
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    docker compose exec freeradius corebalt-radtest @args
} finally {
    Pop-Location
}
exit $LASTEXITCODE
