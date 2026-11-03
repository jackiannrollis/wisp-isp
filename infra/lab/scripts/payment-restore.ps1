<#
.SYNOPSIS
    Verify a manual lab payment restores CB000001 through the real tunnel worker and CHR.
.DESCRIPTION
    DEVELOPER LAB ONLY. Run after onboarding and the restricted-mode check. Uses the seeded lab
    account and localhost API only; records one fake cash payment, replays its idempotency key,
    and checks renewal, the new PPP session, its queue, ledger credit and receipt. Does not send
    a manual Disconnect or edit the expiry. The manual payment is outside Gate 1 (ADR 0018).
#>
param(
    [Parameter(Mandatory)] [string] $RouterAddress
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$routerIp = [Net.IPAddress]::Parse($RouterAddress)
if ($routerIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'RouterAddress must be IPv4' }
$values = Read-DotEnv
$apiPort = Get-EnvValue $values 'BACKEND_PORT' '8080'
$api = "http://localhost:$apiPort/api/v1"
$customerId = '01900000-0000-7000-8000-0000000c0001'
$lineId = '01900000-0000-7000-8000-0000000b0001'
$routerId = '01900000-0000-7000-8000-0000000000a2'
$session = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body (
    @{tenant='CB';email='lab-admin@corebalt.example.test';password='lab-admin-password'} | ConvertTo-Json)
$headers = @{Authorization="Bearer $($session.accessToken)"}
$routerHeaders = @{Authorization=('Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    'admin:' + (Get-EnvValue $values 'LAB_CHR_ADMIN_PASSWORD'))))}

function Get-LabSession {
    $rows = Invoke-RestMethod -Uri "http://$routerIp/rest/ppp/active" -Headers $routerHeaders -TimeoutSec 10
    return $rows | Where-Object name -eq 'CB000001' | Select-Object -First 1
}

$beforeLine = Invoke-RestMethod -Uri "$api/subscriptions/$lineId" -Headers $headers
$beforeSession = Get-LabSession
if ($beforeLine.accessProfile -ne 'RESTRICTED' -or $beforeLine.accessReason -ne 'EXPIRED' -or
    -not $beforeSession -or $beforeSession.address -notlike '10.11.0.*') {
    throw 'First expire CB000001 and disconnect it into the lab restricted pool, then run this check.'
}
$package = Invoke-RestMethod -Uri "$api/packages/$($beforeLine.packageId)" -Headers $headers
$wallet = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet" -Headers $headers
$beforeEntries = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet/entries?size=200" -Headers $headers
$beforeEntryIds = @($beforeEntries.items | ForEach-Object id)
$amount = [long]$package.price.amount - [long]$wallet.balance.amount
if ($amount -le 0) { throw 'The lab wallet already covers the renewal; no payment test was started.' }

$key = [guid]::NewGuid().ToString()
$paymentHeaders = @{Authorization=$headers.Authorization;'Idempotency-Key'=$key}
$body = @{customerId=$customerId;amount=@{amount=$amount;currency='KES'};channel='CASH';
    reference=('CHR-' + $key);note='Fake cash payment for the isolated CHR restore check'} | ConvertTo-Json
$timer = [Diagnostics.Stopwatch]::StartNew()
$payment = Invoke-RestMethod -Method Post -Uri "$api/payments/manual" -Headers $paymentHeaders -ContentType 'application/json' -Body $body
Write-Host "Lab payment $($payment.id) recorded; waiting for automatic renewal and reconnection."

$restored = $false
while ($timer.Elapsed.TotalSeconds -lt 60) {
    $line = Invoke-RestMethod -Uri "$api/subscriptions/$lineId" -Headers $headers -TimeoutSec 10
    $active = Get-LabSession
    if ($line.accessProfile -eq 'FULL' -and $active -and $active.address -like '10.10.0.*' -and
        $active.'session-id' -ne $beforeSession.'session-id') {
        $restored = $true
        break
    }
    Start-Sleep -Seconds 1
}
$timer.Stop()
if (-not $restored) { throw 'Payment did not automatically restore the CHR session within 60 seconds.' }

$queueRows = Invoke-RestMethod -Uri "http://$routerIp/rest/queue/simple" -Headers $routerHeaders -TimeoutSec 10
$queue = $queueRows | Where-Object name -eq '<pppoe-CB000001>' | Select-Object -First 1
if ($queue.'max-limit' -ne '5000000/10000000') { throw 'The restored session has the wrong queue speed.' }
$replay = Invoke-WebRequest -Method Post -Uri "$api/payments/manual" -Headers $paymentHeaders -ContentType 'application/json' -Body $body
if ($replay.Headers['Idempotent-Replayed'] -notcontains 'true' -or
    ($replay.Content | ConvertFrom-Json).id -ne $payment.id) { throw 'Payment replay was not idempotent.' }
$entries = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet/entries?size=200" -Headers $headers
$credits = @($entries.items | Where-Object { $_.type -eq 'PAYMENT' -and $_.referenceId -eq $payment.id })
if ($credits.Count -ne 1) { throw 'The payment must have exactly one ledger credit.' }
$charges = @($entries.items | Where-Object { $_.type -eq 'RENEWAL_CHARGE' -and $_.id -notin $beforeEntryIds })
if ($charges.Count -ne 1 -or $charges[0].amount.amount -ne -[long]$package.price.amount) {
    throw 'The payment must fund exactly one full-price renewal charge.'
}
$afterWallet = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet" -Headers $headers
if ($afterWallet.balance.amount -ne 0) { throw 'The renewal must consume the funded wallet balance.' }
$receipts = Invoke-RestMethod -Uri "$api/receipts?customerId=$customerId&size=200" -Headers $headers
$receipt = @($receipts.items | Where-Object paymentId -eq $payment.id)
if ($receipt.Count -ne 1) { throw 'The payment must have exactly one receipt.' }
Invoke-RestMethod -Method Post -Uri "$api/routers/$routerId/poll" -Headers $headers | Out-Null

$result = [ordered]@{
    completedAt=(Get-Date).ToString('o');paymentId=$payment.id;amountMinor=$amount;
    beforeProfile=$beforeLine.accessProfile;afterProfile=$line.accessProfile;
    beforeSessionId=$beforeSession.'session-id';afterSessionId=$active.'session-id';
    restoredAddress=$active.address;queueLimit=$queue.'max-limit';
    observedRestoreSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3);
    paymentCredits=$credits.Count;renewalCharges=$charges.Count;finalBalanceMinor=$afterWallet.balance.amount;
    receipts=$receipt.Count;idempotentReplay=$true
}
$outDir = Join-Path $LabDir 'out'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$result | ConvertTo-Json | Set-Content (Join-Path $outDir 'payment-restore.result.json')
$result | ConvertTo-Json
