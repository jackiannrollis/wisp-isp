# Follow logs, optionally for given services: .\logs.ps1 backend freeradius
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
docker compose logs -f --tail=200 @args
