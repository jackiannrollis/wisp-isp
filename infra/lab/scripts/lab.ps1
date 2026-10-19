<#
.SYNOPSIS
    Network lab driver (step 2 of the v1 build order, docs/09): two MikroTik CHR VMs in Hyper-V, a
    router peered with the compose WireGuard hub and a PPPoE client, plus RADIUS CoA/Disconnect.

.DESCRIPTION
    Commands (run from any directory; the compose stack must be up and seeded, see README):
      create                      download and verify CHR, create the private switch and both VMs, start them
      onboard                     configure the router (lab base + onboarding script) and the client, peer
                                  the router with the hub, load the lab fixtures, dial CB000001
      status                      tunnel, RADIUS client, PPPoE sessions and queues on the router
      login <user>                redial the client as <user> (password lab-password)
      check                       from the client: HTTP, HTTPS, ping and DNS, to prove FULL or RESTRICTED
      set-line <user> <interval>  move a line's expires_at to now() + interval (e.g. '-10 days')
      coa <user> <rate>           RADIUS CoA: change Mikrotik-Rate-Limit in place (e.g. 2M/4M)
      disconnect <user>           RADIUS Disconnect-Request: drop the session so the client redials
      stop | start | destroy      stop, start or delete both VMs (destroy keeps the downloaded image)

    Requires Hyper-V and membership of "Hyper-V Administrators" (or an elevated shell).
    LAB ONLY: the routers are managed over plain HTTP REST on the Hyper-V Default Switch.
#>
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet("create", "onboard", "status", "login", "check", "set-line", "coa", "disconnect", "stop", "start", "destroy")]
    [string] $Command,
    [Parameter(Position = 1)] [string] $Arg1,
    [Parameter(Position = 2)] [string] $Arg2
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

# Pinned RouterOS image: current stable on 2026-10-19 and its SHA-256 as published by MikroTik
# (https://download.mikrotik.com/routeros/<version>/chr-<version>.vhdx.zip.sha256).
$RouterOsVersion = "7.24.5"
$ImageSha256 = "4a5efa354a29505e66dc1aff1206ef251d0b27577074c33e9a7545a7f1bb0495"

$RouterVm = "corebalt-lab-router"
$ClientVm = "corebalt-lab-client"
$LanSwitch = "corebalt-lab-lan"
$WanSwitch = "Default Switch"
$ImageDir = Join-Path $LabDir "images"
$VmDir = Join-Path $LabDir "out\vms"
$BaseImage = Join-Path $ImageDir "chr-$RouterOsVersion.vhdx"
$TemplateDir = Join-Path $LabDir "routeros"

$values = Read-DotEnv
$AdminPassword = Get-EnvValue $values "LAB_CHR_ADMIN_PASSWORD"
$RadiusSecret = Get-EnvValue $values "RADIUS_LAB_SECRET"
$ChrTunnelIp = Get-EnvValue $values "LAB_CHR_TUNNEL_IP" "10.200.0.11"
$HubAddress = Get-EnvValue $values "WG_HUB_ADDRESS" "10.200.0.1/24"
$HubTunnelIp = $HubAddress.Split("/")[0]
$HubPrefix = $HubAddress.Split("/")[1]
$WgPort = Get-EnvValue $values "WG_PORT" "51820"
$BackendPort = Get-EnvValue $values "BACKEND_PORT" "8080"
$PayPagePort = Get-EnvValue $values "CADDY_PAYPAGE_PORT" "8089"

# --- helpers -------------------------------------------------------------------------------------

function Invoke-Compose {
    Push-Location $InfraDir
    try {
        $output = docker compose @args
        if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') failed" }
        return $output
    } finally { Pop-Location }
}

function Get-VmAddress([string] $Name) {
    # CHR reports its addresses through Hyper-V integration (KVP); fall back to the host's ARP cache.
    $deadline = (Get-Date).AddMinutes(3)
    $adapter = Get-VMNetworkAdapter -VMName $Name | Where-Object SwitchName -eq $WanSwitch
    $mac = ($adapter.MacAddress -replace '(..)(?!$)', '$1-').ToLower()
    while ((Get-Date) -lt $deadline) {
        $ip = (Get-VMNetworkAdapter -VMName $Name | Where-Object SwitchName -eq $WanSwitch).IPAddresses |
            Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' } | Select-Object -First 1
        if (-not $ip) {
            $ip = Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Where-Object { $_.LinkLayerAddress.ToLower() -eq $mac -and $_.State -ne "Unreachable" } |
                Select-Object -ExpandProperty IPAddress -First 1
        }
        if ($ip) { return $ip }
        Start-Sleep -Seconds 3
    }
    throw "no IPv4 address for $Name on '$WanSwitch' after 3 minutes"
}

function New-RosHeaders([string] $Password) {
    $token = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$Password"))
    return @{ Authorization = "Basic $token" }
}

function Invoke-Ros([string] $Address, [string] $Script, [string] $Password = $AdminPassword) {
    $body = @{ script = $Script; "as-string" = "" } | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Method Post -Uri "http://$Address/rest/execute" -Headers (New-RosHeaders $Password) `
        -ContentType "application/json" -Body $body -TimeoutSec 60
    return $result.ret
}

function Get-Ros([string] $Address, [string] $Path) {
    return Invoke-RestMethod -Uri "http://$Address/rest/$Path" -Headers (New-RosHeaders $AdminPassword) -TimeoutSec 30
}

function Wait-Ros([string] $Address) {
    # Waits for REST, then sets the admin password on a fresh CHR (admin without a password).
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        try { Get-Ros $Address "system/resource" | Out-Null; return } catch {}
        try {
            Invoke-Ros $Address "/user set admin password=`"$AdminPassword`"" "" | Out-Null
            Write-Host "  admin password set on $Address"
            return
        } catch {}
        Start-Sleep -Seconds 3
    }
    throw "RouterOS REST on $Address did not answer"
}

function Format-Template([string] $Name, [hashtable] $Values) {
    $text = Get-Content -Raw (Join-Path $TemplateDir $Name)
    foreach ($key in $Values.Keys) { $text = $text.Replace("{{$key}}", [string] $Values[$key]) }
    if ($text -match '\{\{([A-Z_0-9]+)\}\}') { throw "template ${Name}: no value for $($Matches[1])" }
    return $text
}

function Get-NetworkOf([string] $Cidr) {
    $ip, $bits = $Cidr.Split("/")
    $bytes = ([Net.IPAddress]::Parse($ip)).GetAddressBytes()
    [Array]::Reverse($bytes)
    $mask = [uint32]::MaxValue -shl (32 - [int] $bits)
    $net = [BitConverter]::GetBytes([BitConverter]::ToUInt32($bytes, 0) -band $mask)
    [Array]::Reverse($net)
    return "$([Net.IPAddress]::new($net))/$bits"
}

function New-RandomToken {
    return -join ((1..24) | ForEach-Object { "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"[(Get-Random -Maximum 55)] })
}

function Send-Radius([string] $Type, [string[]] $Attributes) {
    # CoA/Disconnect leave from the FreeRADIUS container, which shares the hub's network namespace,
    # so they reach the router over the tunnel from the hub address its /radius entry trusts (N5, N6).
    $lines = ($Attributes -join "`n") + "`n"
    $env:RADIUS_ATTRS = $lines
    try {
        Push-Location $InfraDir
        docker compose exec -T -e RADIUS_ATTRS freeradius sh -c `
            'printf "%s" "$RADIUS_ATTRS" | radclient -x -r 3 -t 3 "$1:3799" "$2" "$RADIUS_LAB_SECRET"' sh $ChrTunnelIp $Type
    } finally {
        Pop-Location
        Remove-Item Env:RADIUS_ATTRS -ErrorAction SilentlyContinue
    }
}

function Assert-Username([string] $User) {
    if ($User -notmatch '^[A-Z]{2,4}[0-9]{6}(-[0-9]+)?$') { throw "not a lab username: '$User'" }
}

function Show-RouterSessions([string] $Router) {
    Write-Host "PPPoE sessions on the router:"
    Get-Ros $Router "ppp/active" | Format-Table name, address, uptime, caller-id -AutoSize | Out-Host
    Write-Host "Queues (Mikrotik-Rate-Limit as upload/download):"
    Get-Ros $Router "queue/simple" | Format-Table name, target, max-limit -AutoSize | Out-Host
    Write-Host "Address lists:"
    Get-Ros $Router "ip/firewall/address-list" | Where-Object { $_.list -in "restricted", "throttled" } |
        Format-Table list, address, dynamic -AutoSize | Out-Host
}

function Set-ClientUser([string] $Client, [string] $User) {
    Invoke-Ros $Client "/interface pppoe-client set [find name=pppoe-out1] user=`"$User`" disabled=yes" | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Ros $Client "/interface pppoe-client set [find name=pppoe-out1] disabled=no" | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $state = Get-Ros $Client "interface/pppoe-client"
        if ($state.running -eq "true") { Write-Host "  $User dialled in"; return $true }
        Start-Sleep -Seconds 2
    }
    Write-Host "  $User is not connected after 30 s (REJECT profiles never connect: N3)"
    return $false
}

# --- commands ------------------------------------------------------------------------------------

switch ($Command) {
    "create" {
        New-Item -ItemType Directory -Force $ImageDir, $VmDir | Out-Null
        if (-not (Test-Path $BaseImage)) {
            $zip = Join-Path $ImageDir "chr-$RouterOsVersion.vhdx.zip"
            Write-Host "Downloading RouterOS CHR $RouterOsVersion"
            Invoke-WebRequest "https://download.mikrotik.com/routeros/$RouterOsVersion/chr-$RouterOsVersion.vhdx.zip" -OutFile $zip
            $hash = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
            if ($hash -ne $ImageSha256) { Remove-Item $zip; throw "SHA-256 mismatch for $zip ($hash)" }
            Expand-Archive $zip -DestinationPath $ImageDir -Force
            Remove-Item $zip
            Set-ItemProperty $BaseImage -Name IsReadOnly -Value $true
        }
        if (-not (Get-VMSwitch -Name $WanSwitch -ErrorAction SilentlyContinue)) { throw "Hyper-V '$WanSwitch' not found" }
        if (-not (Get-VMSwitch -Name $LanSwitch -ErrorAction SilentlyContinue)) {
            New-VMSwitch -Name $LanSwitch -SwitchType Private -Notes "Corebalt network lab: PPPoE customer LAN" | Out-Null
        }
        foreach ($name in $RouterVm, $ClientVm) {
            if (Get-VM -Name $name -ErrorAction SilentlyContinue) { Write-Host "$name exists"; continue }
            $disk = Join-Path $VmDir "$name.vhdx"
            New-VHD -Path $disk -ParentPath $BaseImage -Differencing | Out-Null
            # CHR supports only Generation 1 VMs on Hyper-V, with the system disk on IDE (MikroTik CHR docs).
            New-VM -Name $name -Generation 1 -MemoryStartupBytes 512MB -VHDPath $disk -SwitchName $WanSwitch -Path $VmDir | Out-Null
            Set-VM -Name $name -StaticMemory -ProcessorCount 1 -AutomaticCheckpointsEnabled $false `
                -AutomaticStartAction Nothing -AutomaticStopAction ShutDown -Notes "Corebalt network lab"
            Add-VMNetworkAdapter -VMName $name -SwitchName $LanSwitch
            Start-VM -Name $name
            Write-Host "$name created and started"
        }
        foreach ($name in $RouterVm, $ClientVm) { Write-Host "$name at $(Get-VmAddress $name)" }
    }

    "onboard" {
        $router = Get-VmAddress $RouterVm
        $client = Get-VmAddress $ClientVm
        Wait-Ros $router
        Wait-Ros $client

        $wan = Get-Ros $router "ip/dhcp-client" | Where-Object interface -eq "ether1" | Select-Object -First 1
        if (-not $wan.gateway) { throw "router has no DHCP gateway on ether1 yet" }
        # The Default Switch gateway is the Windows host: it publishes the hub's UDP port and Caddy.
        $hostAddress = $wan.gateway
        $wanNetwork = Get-NetworkOf $wan.address
        Write-Host "Router $router, client $client, host (hub endpoint and pay page) $hostAddress"

        Write-Host "Applying lab base and onboarding script to the router"
        Invoke-Ros $router (Format-Template "lab-base.rsc.tmpl" @{ ROUTER_NAME = "cb-lab-chr-1"; WAN_INTERFACE = "ether1" }) | Out-Null
        $hubKey = (Invoke-Compose exec -T wireguard wireguard-hub public-key | Select-Object -Last 1).Trim()
        $restPassword = New-RandomToken
        $onboarding = Format-Template "onboarding.rsc.tmpl" @{
            ROUTER_NAME        = "cb-lab-chr-1"
            ROUTER_ID          = "01900000-0000-7000-8000-0000000000a2"
            HUB_A_PUBLIC_KEY   = $hubKey
            HUB_A_ENDPOINT     = $hostAddress
            HUB_A_PORT         = $WgPort
            HUB_A_TUNNEL_IP    = $HubTunnelIp
            TUNNEL_ADDRESS     = "$ChrTunnelIp/$HubPrefix"
            TUNNEL_IP          = $ChrTunnelIp
            RADIUS_SECRET      = $RadiusSecret
            FULL_POOL          = "10.10.0.10-10.10.0.250"
            RESTRICTED_POOL    = "10.11.0.10-10.11.0.250"
            PPP_LOCAL_ADDRESS  = "10.10.0.1"
            PPPOE_INTERFACE    = "ether2"
            PORTAL_ADDRESS     = $hostAddress
            PAYPAGE_PORT       = $PayPagePort
            REST_PASSWORD      = $restPassword
            SNMP_COMMUNITY     = New-RandomToken
            # LAB ONLY: production allows the hub tunnel only (N6); the lab also manages over the Default Switch.
            MANAGEMENT_ALLOWED = "$(Get-NetworkOf $HubAddress),$wanNetwork"
            NTP_SERVERS        = "pool.ntp.org"
        }
        Invoke-Ros $router $onboarding | Out-Null

        $wg = Get-Ros $router "interface/wireguard" | Where-Object name -eq "wg-corebalt"
        Invoke-Compose exec -T wireguard wireguard-hub add-peer cb-lab-chr-1 $wg."public-key" "$ChrTunnelIp/32" | Out-Host

        Write-Host "Loading lab fixtures and restarting FreeRADIUS"
        & (Join-Path $PSScriptRoot "seed.ps1") | Out-Null

        # The tunnel worker polls the router's REST API with this login (docs/06 Monitoring); the
        # API stores it envelope-encrypted and never returns it. Sent in the body, never as an argument.
        Write-Host "Giving the platform the router's REST login (monitoring)"
        $api = "http://localhost:$BackendPort/api/v1"
        $session = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType "application/json" `
            -Body (@{ tenant = "CB"; email = "lab-admin@corebalt.example.test"; password = "lab-admin-password" } | ConvertTo-Json)
        Invoke-RestMethod -Method Put -Uri "$api/routers/01900000-0000-7000-8000-0000000000a2/rest-credential" `
            -Headers @{ Authorization = "Bearer $($session.accessToken)" } -ContentType "application/json" `
            -Body (@{ username = "corebalt-api"; password = $restPassword } | ConvertTo-Json) | Out-Null

        $ping = Invoke-Ros $router ":put [/ping $HubTunnelIp count=3]"
        Write-Host "Tunnel: router -> hub $HubTunnelIp, $($ping.Trim())/3 replies"

        Write-Host "Configuring the PPPoE client as CB000001"
        Invoke-Ros $client (Format-Template "pppoe-client.rsc.tmpl" @{
            CLIENT_NAME = "cb-lab-customer"; MGMT_INTERFACE = "ether1"; PPPOE_INTERFACE = "ether2"
            PPPOE_USER = "CB000001"; PPPOE_PASSWORD = "lab-password"
        }) | Out-Null
        Set-ClientUser $client "CB000001" | Out-Null
        Show-RouterSessions $router
    }

    "status" {
        $router = Get-VmAddress $RouterVm
        Write-Host "WireGuard on the hub:"
        Invoke-Compose exec -T wireguard wg show wg0 | Out-Host
        Write-Host "RADIUS clients known to FreeRADIUS come from radius_client_v3 (loaded at start)."
        Show-RouterSessions $router
    }

    "login" {
        Assert-Username $Arg1
        $client = Get-VmAddress $ClientVm
        Set-ClientUser $client $Arg1 | Out-Null
        Show-RouterSessions (Get-VmAddress $RouterVm)
    }

    "check" {
        $client = Get-VmAddress $ClientVm
        $http = Invoke-Ros $client ':do { :put ([/tool fetch url="http://example.com/" output=user as-value]->"data") } on-error={ :put "FETCH-FAILED" }'
        $https = Invoke-Ros $client ':do { /tool fetch url="https://example.com/" output=none; :put "REACHABLE" } on-error={ :put "BLOCKED" }'
        $ping = Invoke-Ros $client ':put [/ping 1.1.1.1 count=3]'
        $dns = Invoke-Ros $client ':do { :put [:resolve example.com] } on-error={ :put "DNS-FAILED" }'
        $payPage = $http -match "corebalt-lab-paypage"
        Write-Host ("HTTP  http://example.com/  -> " + $(if ($payPage) { "redirected to the pay page" } elseif ($http -match "Example Domain") { "the real site" } else { $http.Trim() }))
        Write-Host "HTTPS https://example.com/ -> $($https.Trim())"
        Write-Host "PING  1.1.1.1              -> $($ping.Trim())/3 replies"
        Write-Host "DNS   example.com          -> $($dns.Trim())"
        if ($payPage -and $https.Trim() -eq "BLOCKED" -and $ping.Trim() -eq "0" -and $dns -notmatch "FAILED") {
            Write-Host "RESTRICTED as designed (docs/06): DNS works, HTTP goes to the pay page, everything else is dropped."
        } elseif ($http -match "Example Domain" -and $https.Trim() -eq "REACHABLE") {
            Write-Host "FULL internet access."
        } else {
            Write-Host "Neither FULL nor RESTRICTED: check 'lab.ps1 status' and the FreeRADIUS logs."
            exit 1
        }
    }

    "set-line" {
        Assert-Username $Arg1
        if ($Arg2 -notmatch '^-?\d+ (minute|hour|day)s?$') { throw "interval like '30 days' or '-10 days'" }
        $env:PGPASSWORD = Get-EnvValue $values "ISP_DB_OWNER_PASSWORD"
        try {
            Invoke-Compose exec -T -e PGPASSWORD postgres psql -h localhost -U isp_owner -d isp -c `
                "update subscriptions_subscription set expires_at = now() + interval '$Arg2', updated_at = now() where pppoe_username = '$Arg1' returning pppoe_username, state, expires_at" | Out-Host
        } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        Write-Host "The live session keeps its profile until Session-Timeout or a Disconnect (lab.ps1 disconnect $Arg1)."
    }

    "coa" {
        Assert-Username $Arg1
        if ($Arg2 -notmatch '^[0-9]+[kM]?/[0-9]+[kM]?$') { throw "rate like 2M/4M (upload/download, N4)" }
        Send-Radius "coa" @("User-Name = $Arg1", "Mikrotik-Rate-Limit = `"$Arg2`"")
        Show-RouterSessions (Get-VmAddress $RouterVm)
    }

    "disconnect" {
        Assert-Username $Arg1
        Send-Radius "disconnect" @("User-Name = $Arg1")
        Start-Sleep -Seconds 8
        Show-RouterSessions (Get-VmAddress $RouterVm)
    }

    "stop" { Stop-VM -Name $RouterVm, $ClientVm -Force }
    "start" { Start-VM -Name $RouterVm, $ClientVm }

    "destroy" {
        foreach ($name in $RouterVm, $ClientVm) {
            if (Get-VM -Name $name -ErrorAction SilentlyContinue) {
                Stop-VM -Name $name -TurnOff -Force -ErrorAction SilentlyContinue
                Remove-VM -Name $name -Force
            }
            Remove-Item (Join-Path $VmDir "$name.vhdx") -ErrorAction SilentlyContinue
        }
        if (Get-VMSwitch -Name $LanSwitch -ErrorAction SilentlyContinue) { Remove-VMSwitch -Name $LanSwitch -Force }
        Write-Host "Lab VMs removed; the image in infra/lab/images is kept. Hub peer: re-added by the next onboard."
    }
}
