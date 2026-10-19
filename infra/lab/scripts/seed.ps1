# Load the network-lab fixtures (infra/lab/seed.sql) into the running stack and restart FreeRADIUS,
# which reads its NAS list only at startup. Safe to re-run: it resets the lab lines' dates.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$values = Read-DotEnv
# Secrets travel as environment variables (docker compose exec -e NAME), never as arguments.
$env:LAB_SECRET = Get-EnvValue $values "RADIUS_LAB_SECRET"
$env:CHR_TUNNEL_IP = Get-EnvValue $values "LAB_CHR_TUNNEL_IP" "10.200.0.11"
$env:PGPASSWORD = Get-EnvValue $values "ISP_DB_OWNER_PASSWORD"

Push-Location $InfraDir
try {
    Get-Content -Raw (Join-Path $LabDir "seed.sql") |
        docker compose exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres `
            psql -h localhost -U isp_owner -d isp -q
    if ($LASTEXITCODE -ne 0) { throw "seeding failed" }
    docker compose restart freeradius
} finally {
    Remove-Item Env:LAB_SECRET, Env:CHR_TUNNEL_IP, Env:PGPASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
exit $LASTEXITCODE
