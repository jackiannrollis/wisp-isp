# Stop the local stack. Pass -v to also delete the database and broker volumes.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose down @args
exit $LASTEXITCODE
