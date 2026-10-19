# Break-glass reset of a platform admin (ADR 0010): reset-platform-admin.ps1 <email>
# Runs inside the backend container (never over HTTP). Prints a temporary password once, clears TOTP
# and recovery codes, ends every session and writes the platform audit log with ISP_OPERATOR (set it
# to your name; the OS user otherwise).
param([Parameter(Mandatory = $true)][string]$Email)
$ErrorActionPreference = "Stop"
if (-not $env:ISP_OPERATOR) { $env:ISP_OPERATOR = $env:USERNAME }
Push-Location (Join-Path $PSScriptRoot "..")
try {
    docker compose exec -e ISP_OPERATOR backend java -jar /app/app.jar reset-platform-admin $Email
} finally {
    Pop-Location
}
exit $LASTEXITCODE
