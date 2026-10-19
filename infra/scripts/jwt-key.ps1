# Creates or rotates the access-token signing key (ADR 0010) in infra/.env.
#   jwt-key.ps1           create a key if ISP_JWT_PRIVATE_KEY is empty (does nothing otherwise)
#   jwt-key.ps1 -Rotate   new key for new tokens; the old public key moves to ISP_JWT_RETIRED_KEYS,
#                         so tokens already issued stay valid until they expire (15 min access,
#                         refresh tokens are opaque and unaffected). Remove retired keys after a day.
# The private key is written only to infra/.env (git-ignored), never printed.
param([switch]$Rotate)
$ErrorActionPreference = "Stop"
$envFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $envFile)) { throw "infra/.env missing: copy infra/.env.example first" }

$lines = [System.Collections.Generic.List[string]](Get-Content $envFile)
function Get-Value([string]$name) {
    $line = $lines | Where-Object { $_ -like "$name=*" } | Select-Object -First 1
    if ($line) { return $line.Substring($name.Length + 1) } else { return "" }
}
function Set-Value([string]$name, [string]$value) {
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -like "$name=*") { $lines[$i] = "$name=$value"; return }
    }
    $lines.Add("$name=$value")
}

$current = Get-Value "ISP_JWT_PRIVATE_KEY"
if ($current -like "change-me*") { $current = "" }
if ($current -and -not $Rotate) {
    Write-Host "ISP_JWT_PRIVATE_KEY is already set (kid $(Get-Value 'ISP_JWT_KID')); use -Rotate to replace it."
    exit 0
}

$retired = Get-Value "ISP_JWT_RETIRED_KEYS"
if ($current) {
    $old = [System.Security.Cryptography.RSA]::Create()
    $read = 0
    $old.ImportPkcs8PrivateKey([Convert]::FromBase64String($current), [ref]$read)
    $entry = "$(Get-Value 'ISP_JWT_KID')=" + [Convert]::ToBase64String($old.ExportSubjectPublicKeyInfo())
    $retired = if ($retired) { "$retired,$entry" } else { $entry }
}

$rsa = [System.Security.Cryptography.RSA]::Create(3072)
$kid = "k" + (Get-Date -AsUTC -Format "yyyyMMddHHmm")
Set-Value "ISP_JWT_KID" $kid
Set-Value "ISP_JWT_PRIVATE_KEY" ([Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()))
Set-Value "ISP_JWT_RETIRED_KEYS" $retired
Set-Content -Path $envFile -Value $lines -Encoding utf8NoBOM
Write-Host "Signing key $kid written to infra/.env. Restart the backend: docker compose up -d backend"
