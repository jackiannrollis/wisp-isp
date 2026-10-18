# Run every automated check: backend unit + Testcontainers tests, frontend lint, build and
# tests, compose validation. Needs JDK 25 (JAVA_HOME), Node 24 and Docker.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
function Invoke-Step([string] $dir, [scriptblock] $block) {
    Push-Location $dir
    try { & $block; if ($LASTEXITCODE -ne 0) { throw "Failed in $dir" } } finally { Pop-Location }
}
Invoke-Step "$root/backend" { .\mvnw.cmd -B verify }
Invoke-Step "$root/frontend" {
    npm ci; if ($LASTEXITCODE -ne 0) { return }
    npm run lint; if ($LASTEXITCODE -ne 0) { return }
    npm run format:check; if ($LASTEXITCODE -ne 0) { return }
    npm run build; if ($LASTEXITCODE -ne 0) { return }
    npm run test
}
Invoke-Step "$root/infra" { docker compose --env-file .env.example config --quiet }
Write-Host "All checks passed."
