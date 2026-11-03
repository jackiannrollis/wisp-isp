# Disposable browser-regression stack. -Fresh resets only corebalt-e2e's fake fixture database.
param([switch]$Seed, [switch]$Fresh)
$ErrorActionPreference = 'Stop'
$InfraDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $InfraDir
try {
    node e2e/create-env.mjs
    if ($LASTEXITCODE) { throw 'Browser-test key setup failed' }
    $files = @('-p','corebalt-e2e','--env-file','e2e/.env','-f','e2e/compose.isolated.yml')
    if ($Fresh) {
        docker compose @files down --volumes --remove-orphans
        if ($LASTEXITCODE) { throw 'Resetting the isolated browser-test project failed' }
    }
    docker compose @files up -d --build --wait
    if ($LASTEXITCODE) { throw 'The isolated browser stack did not start' }
    if ($Seed) {
        $env:LAB_SECRET = 'fake-e2e-radius-secret'
        $env:CHR_TUNNEL_IP = '10.200.0.11'
        $env:PGPASSWORD = 'fake-e2e-owner-password'
        Get-Content -Raw (Join-Path $InfraDir 'lab/seed.sql') |
            docker compose @files exec -T -e LAB_SECRET -e CHR_TUNNEL_IP -e PGPASSWORD postgres `
                psql -h localhost -U isp_owner -d isp -q -v ON_ERROR_STOP=1
        if ($LASTEXITCODE) { throw 'Browser fixture seeding failed' }
    }
    docker compose @files ps
} finally {
    Remove-Item Env:LAB_SECRET,Env:CHR_TUNNEL_IP,Env:PGPASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
