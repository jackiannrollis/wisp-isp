# The operator's entry point on Windows (decisions.md 2026-10-21): runs Ansible, cosign and crane from
# the ops image, so only Docker Desktop is needed (no WSL). The repository is mounted read-only (the
# inventories folder read-write, for the secrets file), the SSH key read-only, and known host keys
# persist in the volume corebalt-ops-ssh.
#
#   infra\ops\ops.ps1 lint                                       ansible-lint and a syntax check
#   infra\ops\ops.ps1 secrets staging                            create the inventory's encrypted secrets
#   infra\ops\ops.ps1 bootstrap staging -Limit host              first run on a new server, as root (once)
#   infra\ops\ops.ps1 provision staging [-Limit host] [-Check]   hardening, data disk, hub, stack (site.yml)
#   infra\ops\ops.ps1 init-disk staging -Limit host              encrypt the data disk (erases it, asks twice)
#   infra\ops\ops.ps1 unlock staging -Limit host                 open the LUKS data disk after a reboot
#   infra\ops\ops.ps1 shell                                      a shell in the toolbox
#
# Inventories live in infra/ansible/inventories/<name>/ (only "example" is committed). Secrets are
# never arguments: the vault password and passphrases are prompted for.
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet("lint", "secrets", "bootstrap", "provision", "init-disk", "unlock", "shell", "build")]
    [string]$Command,
    [Parameter(Position = 1)]
    [string]$Inventory,
    [string]$Limit,
    [switch]$Check,
    [string]$SshKey = (Join-Path $HOME ".ssh\id_ed25519")
)
$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Image = "corebalt-ops:local"

function Build-Image {
    docker build -t $Image -f (Join-Path $Repo "infra\ops\Dockerfile") (Join-Path $Repo "infra")
    if ($LASTEXITCODE -ne 0) { throw "building the ops image failed" }
}

if ($Command -eq "build") { Build-Image; exit 0 }
docker image inspect $Image *> $null
if ($LASTEXITCODE -ne 0) { Build-Image }

$Inventories = Join-Path $Repo "infra\ansible\inventories"
$run = @("run", "--rm", "-it",
    "-v", "${Repo}:/repo:ro",
    "-v", "${Inventories}:/repo/infra/ansible/inventories",
    "-v", "corebalt-ops-ssh:/home/ops/.ssh/known")
if (Test-Path $SshKey) { $run += @("-v", "${SshKey}:/run/secrets/ssh_key:ro") }
elseif ($Command -notin @("lint", "shell", "secrets")) { throw "SSH key not found: $SshKey (use -SshKey)" }
$run += $Image

function Require-Inventory {
    if (-not $Inventory) { throw "name the inventory, e.g. staging" }
    if (-not (Test-Path (Join-Path $Inventories $Inventory))) { throw "no inventory at infra/ansible/inventories/$Inventory" }
}

function Playbook([string]$playbook) {
    Require-Inventory
    $cmd = @("ansible-playbook", "-i", "inventories/$Inventory", $playbook)
    # An encrypted vault.yml needs its password; the example and the tests use plain files.
    $vault = Join-Path $Inventories "$Inventory\group_vars\all\vault.yml"
    if ((Test-Path $vault) -and ((Get-Content $vault -TotalCount 1) -like '$ANSIBLE_VAULT*')) { $cmd += "--ask-vault-pass" }
    if ($Limit) { $cmd += @("--limit", $Limit) }
    if ($Check) { $cmd += @("--check", "--diff") }
    return $cmd
}

switch ($Command) {
    "lint" { docker @run sh -c "ansible-lint && ansible-playbook -i inventories/example site.yml data-volume-init.yml unlock.yml --syntax-check" }
    "secrets" {
        Require-Inventory
        docker @run bash /repo/infra/ops/secrets-init.sh $Inventory
    }
    "bootstrap" {
        # A new server accepts only root with the key Hetzner installed; this run creates the operator
        # account and then turns root login off, so later runs use "provision".
        if (-not $Limit) { throw "bootstrap one host at a time: -Limit <host>" }
        docker @run @((Playbook "site.yml") + @("-e", "ansible_user=root"))
    }
    "provision" { docker @run @(Playbook "site.yml") }
    "init-disk" {
        if (-not $Limit) { throw "encrypt one host's disk at a time: -Limit <host>" }
        docker @run @(Playbook "data-volume-init.yml")
    }
    "unlock" {
        if (-not $Limit) { throw "unlock one host at a time: -Limit <host>" }
        docker @run @(Playbook "unlock.yml")
    }
    "shell" { docker @run bash }
}
exit $LASTEXITCODE
