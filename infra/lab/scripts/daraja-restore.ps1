<#
.SYNOPSIS
    Verify a real Daraja sandbox callback automatically restores the isolated CHR session.
.DESCRIPTION
    LAB ONLY. Requires CB000001 expired and connected in RESTRICTED, an HTTPS callback origin,
    the seeded localhost API and the supplied sandbox credentials. Uses simulated sandbox money,
    never a real handset STK request. Sends no Disconnect and edits no expiry after payment.
    Checks the RouterOS CHR session, ledger, receipt and reporting_restore evidence. A successful
    run is a sandbox observation, not completion of the pilot's two-cycle Gate 1.
#>
param(
    [Parameter(Mandatory)] [string] $RouterAddress,
    [string] $CredentialsFile
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$routerIp = [Net.IPAddress]::Parse($RouterAddress)
if ($routerIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'RouterAddress must be IPv4' }
$values = Read-DotEnv
if (-not $CredentialsFile) { $CredentialsFile = Join-Path $InfraDir '../backend/.env' }
$provider = Read-DotEnv $CredentialsFile
foreach ($name in $provider.Keys) {
    if ($name.StartsWith('DARAJA_') -and $provider[$name]) { $values[$name] = $provider[$name] }
}
$origin = Get-EnvValue $values CALLBACK_BASE_URL ''
if (-not $origin.StartsWith('https://')) { throw 'Start an HTTPS callback tunnel and restart the backend first' }
$key = Get-EnvValue $values DARAJA_SANDBOX_CONSUMER_KEY ''
$secret = Get-EnvValue $values DARAJA_SANDBOX_CONSUMER_SECRET ''
if (-not $key -or -not $secret) { throw 'Sandbox consumer credentials are required' }
$shortcode = Get-EnvValue $values DARAJA_SANDBOX_C2B_SHORTCODE '600000'
$testPhone = Get-EnvValue $values DARAJA_SANDBOX_TEST_MSISDN '254708374149'
$api = 'http://localhost:' + (Get-EnvValue $values BACKEND_PORT '8080') + '/api/v1'
$customerId = '01900000-0000-7000-8000-0000000c0001'
$lineId = '01900000-0000-7000-8000-0000000b0001'
$login = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType application/json -Body (@{
    tenant='CB';email='lab-admin@corebalt.example.test';password='lab-admin-password'
} | ConvertTo-Json)
if ($login.status -ne 'SIGNED_IN') { throw 'Lab admin sign-in did not complete' }
$headers = @{Authorization="Bearer $($login.accessToken)"}
$routerHeaders = @{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
    'admin:'+(Get-EnvValue $values LAB_CHR_ADMIN_PASSWORD)) ))}
function Get-LabSession {
    $rows = Invoke-RestMethod -Uri "http://$routerIp/rest/ppp/active" -Headers $routerHeaders -TimeoutSec 5
    return $rows | Where-Object name -eq 'CB000001' | Select-Object -First 1
}
function Read-LabJson([string] $Sql) {
    Push-Location $InfraDir
    try {
        $output = docker compose exec -T postgres psql -U postgres -d isp -At -v ON_ERROR_STOP=1 -c $Sql
        if ($LASTEXITCODE -ne 0) { throw 'Read-only lab evidence query failed' }
        return ($output -join "`n") | ConvertFrom-Json
    } finally { Pop-Location }
}
$beforeLine = Invoke-RestMethod -Uri "$api/subscriptions/$lineId" -Headers $headers
$beforeSession = Get-LabSession
if ($beforeLine.accessProfile -ne 'RESTRICTED' -or $beforeLine.accessReason -ne 'EXPIRED' -or
    -not $beforeSession -or $beforeSession.address -notlike '10.11.0.*') {
    throw 'Prepare CB000001 as expired and connected in the restricted pool before this check'
}
$open = Read-LabJson "select to_json(count(*)) from radacct where subscription_id='$lineId' and acctstoptime is null;"
if ([int]$open -lt 1) { throw 'The restricted session must also be visible in accounting before payment' }
$package = Invoke-RestMethod -Uri "$api/packages/$($beforeLine.packageId)" -Headers $headers
$wallet = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet" -Headers $headers
$amount = [long]$package.price.amount - [long]$wallet.balance.amount
if ($amount -le 0 -or $amount % 100 -ne 0) { throw 'The renewal shortfall must be a positive whole-shilling amount' }
$beforeEntries = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet/entries?size=200" -Headers $headers
$beforeEntryIds = @($beforeEntries.items | ForEach-Object id)
$beforePayments = Invoke-RestMethod -Uri "$api/payments?customerId=$customerId&channel=MPESA_PAYBILL&size=200" -Headers $headers
$beforePaymentIds = @($beforePayments.items | ForEach-Object id)
$settings = @{shortcode=$shortcode;shortcodeType='PAYBILL';sandbox=$true;consumerKey=$key;consumerSecret=$secret} | ConvertTo-Json
$view = Invoke-RestMethod -Method Put -Uri "$api/settings/mpesa" -Headers $headers -ContentType application/json -Body $settings
if (-not $view.sandbox -or -not $view.callbackUrls.c2bConfirmation.StartsWith($origin+'/')) {
    throw 'The API is not configured with this sandbox callback origin'
}
Invoke-RestMethod -Method Post -Uri "$api/settings/mpesa/register-urls" -Headers $headers | Out-Null
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${key}:${secret}"))
$token = (Invoke-RestMethod -Uri 'https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials' -Headers @{Authorization="Basic $basic"}).access_token
$body = @{ShortCode=$shortcode;CommandID='CustomerPayBillOnline';Amount=[long]($amount/100);
    Msisdn=$testPhone;BillRefNumber='CB000001'} | ConvertTo-Json
$startedAt = [DateTimeOffset]::UtcNow
$simulation = Invoke-RestMethod -Method Post -Uri 'https://sandbox.safaricom.co.ke/mpesa/c2b/v1/simulate' `
    -Headers @{Authorization="Bearer $token"} -ContentType application/json -Body $body
if ([string]$simulation.ResponseCode -notmatch '^0+$') { throw 'The sandbox simulator did not acknowledge the payment' }
Write-Host "Sandbox payment of KES $($amount/100) accepted; observing automatic CHR restoration."
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
$payment = $null; $observedAt = $null
do {
    $line = Invoke-RestMethod -Uri "$api/subscriptions/$lineId" -Headers $headers -TimeoutSec 5
    $active = Get-LabSession
    if (-not $observedAt -and $line.accessProfile -eq 'FULL' -and $active -and
        $active.address -like '10.10.0.*' -and $active.'session-id' -ne $beforeSession.'session-id') {
        $observedAt = [DateTimeOffset]::UtcNow
    }
    $payments = Invoke-RestMethod -Uri "$api/payments?customerId=$customerId&channel=MPESA_PAYBILL&size=200" -Headers $headers -TimeoutSec 5
    $payment = $payments.items | Where-Object { $_.id -notin $beforePaymentIds -and $_.status -eq 'MATCHED' -and $_.amount.amount -eq $amount } | Select-Object -First 1
    if ($observedAt -and $payment) { break }
    Start-Sleep -Seconds 1
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if (-not $payment -or -not $observedAt) { throw 'No new matched callback payment automatically restored the CHR within 3 minutes' }
$paymentId = ([guid]$payment.id).ToString()
$evidence = Read-LabJson @"
select json_build_object(
 'callbackAt',min(received_at),'callbacks',count(*),
 'processedCallbacks',count(*) filter (where processed_at is not null))
from payments_mpesa_callback where payment_id='$paymentId' and kind='C2B_CONFIRMATION';
"@
if (-not $evidence.callbackAt -or $evidence.processedCallbacks -lt 1) { throw 'The new payment has no processed C2B callback' }
$callbackAt = if ($evidence.callbackAt -is [DateTime]) { [DateTimeOffset]$evidence.callbackAt }
    else { [DateTimeOffset]::Parse($evidence.callbackAt) }
$seconds = ($observedAt-$callbackAt).TotalSeconds
if ($seconds -lt 0 -or $seconds -gt 60) { throw "Observed CHR restoration exceeded the callback's 60-second budget ($seconds seconds)" }
$queues = Invoke-RestMethod -Uri "http://$routerIp/rest/queue/simple" -Headers $routerHeaders -TimeoutSec 5
$queue = $queues | Where-Object name -eq '<pppoe-CB000001>' | Select-Object -First 1
if ($queue.'max-limit' -ne '5000000/10000000') { throw 'The restored lab queue has incorrect speeds' }
$entries = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet/entries?size=200" -Headers $headers
$credits = @($entries.items | Where-Object { $_.type -eq 'PAYMENT' -and $_.referenceId -eq $paymentId })
$charges = @($entries.items | Where-Object { $_.type -eq 'RENEWAL_CHARGE' -and $_.id -notin $beforeEntryIds })
if ($credits.Count -ne 1 -or $charges.Count -ne 1 -or $charges[0].amount.amount -ne -[long]$package.price.amount) {
    throw 'Expected one payment credit and one full-price renewal charge'
}
$afterWallet = Invoke-RestMethod -Uri "$api/customers/$customerId/wallet" -Headers $headers
if ($afterWallet.balance.amount -ne 0) { throw 'The shortfall payment should leave zero after renewal' }
$receipts = Invoke-RestMethod -Uri "$api/receipts?customerId=$customerId&size=200" -Headers $headers
if (@($receipts.items | Where-Object paymentId -eq $paymentId).Count -ne 1) { throw 'Expected one receipt for this payment' }
# Let the platform's existing reporting job settle; never manufacture or update its evidence.
$reportDeadline = [DateTimeOffset]::UtcNow.AddSeconds(75)
do {
    $report = Read-LabJson @"
select row_to_json(r) from (
 select kind,callback_at,renewed_at,restored_at,restored_by,
 extract(epoch from restored_at-callback_at) as restore_seconds
 from reporting_restore where payment_id='$paymentId' and subscription_id='$lineId'
) r;
"@
    if ($report -and $report.restored_at) { break }
    Start-Sleep -Seconds 3
} while ([DateTimeOffset]::UtcNow -lt $reportDeadline)
if (-not $report -or -not $report.restored_at -or $report.kind -ne 'CUT_OFF' -or
    $report.restored_by -notin @('REAUTHORIZED','NEW_SESSION') -or
    $report.restore_seconds -lt 0 -or $report.restore_seconds -gt 60) {
    throw 'The platform did not record a timely CUT_OFF restoration of the live session'
}
$manual = Read-LabJson "select to_json(count(*)) from reporting_manual_reconnect where subscription_id='$lineId' and occurred_at >= '$($startedAt.ToString('o'))'::timestamptz;"
if ([int]$manual -ne 0) { throw 'A manual reconnect was recorded after the sandbox payment began' }
$result = [ordered]@{
    completedAt=[DateTimeOffset]::UtcNow.ToString('o');sandbox=$true;paymentId=$paymentId;amountMinor=$amount;
    walletBeforeMinor=$wallet.balance.amount;callbackAt=$callbackAt.ToString('o');observedFullAt=$observedAt.ToString('o');
    callbackToObservedFullSeconds=[Math]::Round($seconds,3);platformRestore=$report;
    beforeProfile=$beforeLine.accessProfile;afterProfile=$line.accessProfile;
    beforeSessionId=$beforeSession.'session-id';afterSessionId=$active.'session-id';
    restoredAddress=$active.address;queueLimit=$queue.'max-limit';paymentCredits=$credits.Count;
    renewalCharges=$charges.Count;receipts=1;finalBalanceMinor=$afterWallet.balance.amount;
    processedCallbacks=$evidence.processedCallbacks;manualReconnectsAfterPayment=[int]$manual
}
$result | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $LabDir 'out/daraja-restore.result.json')
$result | ConvertTo-Json -Depth 5
