# Shared helpers for the network-lab scripts (dot-sourced, not run directly).

$InfraDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$LabDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

# Reads infra/.env into a hashtable (KEY=VALUE lines; # comments and blank lines ignored).
function Read-DotEnv([string] $Path = (Join-Path $InfraDir '.env')) {
    $path = $Path
    if (-not (Test-Path $path)) { throw "infra/.env missing: copy infra/.env.example and set passwords" }
    $values = @{}
    foreach ($line in Get-Content $path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $key = $Matches[1]; $value = $Matches[2]
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$key] = $value
        }
    }
    return $values
}

# Returns a value from infra/.env, falling back to a default when the key is absent or empty.
function Get-EnvValue([hashtable] $Values, [string] $Key, [string] $Default) {
    if ($Values.ContainsKey($Key) -and $Values[$Key]) { return $Values[$Key] }
    if ($PSBoundParameters.ContainsKey('Default')) { return $Default }
    throw "$Key is not set in infra/.env"
}
