# Start what the end-to-end suite needs (ADR 0012): PostgreSQL, RabbitMQ, Redis, the fake Daraja and
# the backend pointed at it. With -Seed it also loads infra/lab/seed.sql (fresh databases, as in CI).
# infra\scripts\up.ps1 puts the backend back on the real Daraja sandbox afterwards.
param([switch]$Seed)
$ErrorActionPreference = "Stop"
$InfraDir = Join-Path $PSScriptRoot ".."
Push-Location $InfraDir
try {
    if (-not (Test-Path ".env")) { throw "infra/.env missing: copy infra/.env.example and set passwords" }
    $files = @("-f", "docker-compose.yml", "-f", "e2e/compose.e2e.yml")
    docker compose @files up -d --build --wait postgres rabbitmq redis fake-daraja backend
    if ($LASTEXITCODE -ne 0) { throw "the e2e stack did not start" }
    if ($Seed) {
        $values = @{}
        Get-Content ".env" | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
            $name, $value = $_ -split '=', 2
            $values[$name] = $value
        }
        # Secrets travel as environment variables (docker compose exec -e NAME), never as arguments.
        $env:LAB_SECRET = $values["RADIUS_LAB_SECRET"]
        $env:CHR_TUNNEL_IP = if ($values["LAB_CHR_TUNNEL_IP"]) { $values["LAB_CHR_TUNNEL_IP"] } else { "10.200.0.11" }
        $env:PGPASSWORD = $values["ISP_DB_OWNER_PASSWORD"]
        Get-Content -Raw (Join-Path "lab" "seed.sql") |
            docker compose @files exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres `
                psql -h localhost -U isp_owner -d isp -q -v ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) { throw "seeding failed" }
    }
    docker compose @files ps
} finally {
    Remove-Item Env:LAB_SECRET, Env:CHR_TUNNEL_IP, Env:PGPASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
exit $LASTEXITCODE
