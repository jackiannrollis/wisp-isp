# Daraja sandbox round-trip (docs/05, "Sandbox round-trip"). Credentials default to backend/.env; requires
# DARAJA_SANDBOX_CONSUMER_KEY, DARAJA_SANDBOX_CONSUMER_SECRET, DARAJA_SANDBOX_PASSKEY and an https
# CALLBACK_BASE_URL in infra/.env (a tunnel to Caddy :8088), and a running seeded stack; otherwise it says
# what is missing and exits 0.
#
# Steps: configure tenant CB's M-Pesa settings (sandbox), register the C2B URLs, simulate a C2B
# payment for CB000004 and wait for it to be credited and the line renewed, then STK Push the
# sandbox test number for CB000001 and wait for the result. Secrets stay in this process; they are
# never put on a command line or printed.
param([string] $CredentialsFile, [switch] $C2bOnly)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\lab\scripts\common.ps1")
$values = Read-DotEnv
if (-not $CredentialsFile) { $CredentialsFile = Join-Path $InfraDir '../backend/.env' }
if (Test-Path -LiteralPath $CredentialsFile) {
    $providerValues = Read-DotEnv $CredentialsFile
    foreach ($name in @('DARAJA_SANDBOX_CONSUMER_KEY','DARAJA_SANDBOX_CONSUMER_SECRET','DARAJA_SANDBOX_PASSKEY',
        'DARAJA_SANDBOX_SHORTCODE','DARAJA_SANDBOX_C2B_SHORTCODE','DARAJA_SANDBOX_STK_SHORTCODE',
        'DARAJA_SANDBOX_TEST_MSISDN')) {
        if ($providerValues[$name]) { $values[$name] = $providerValues[$name] }
    }
}

$key = Get-EnvValue $values "DARAJA_SANDBOX_CONSUMER_KEY" ""
$secret = Get-EnvValue $values "DARAJA_SANDBOX_CONSUMER_SECRET" ""
$passkey = Get-EnvValue $values "DARAJA_SANDBOX_PASSKEY" ""
$base = Get-EnvValue $values "CALLBACK_BASE_URL" ""
# C2B and M-Pesa Express have different sandbox shortcodes. The old shared key is an STK alias.
$c2bShortcode = Get-EnvValue $values "DARAJA_SANDBOX_C2B_SHORTCODE" "600000"
$stkShortcode = Get-EnvValue $values "DARAJA_SANDBOX_STK_SHORTCODE" (Get-EnvValue $values "DARAJA_SANDBOX_SHORTCODE" "174379")
$testMsisdn = Get-EnvValue $values "DARAJA_SANDBOX_TEST_MSISDN" "254708374149"

$missing = @()
if (-not $key) { $missing += "DARAJA_SANDBOX_CONSUMER_KEY" }
if (-not $secret) { $missing += "DARAJA_SANDBOX_CONSUMER_SECRET" }
if (-not $passkey) { $missing += "DARAJA_SANDBOX_PASSKEY" }
if (-not $base.StartsWith("https://")) { $missing += "CALLBACK_BASE_URL (https tunnel to http://localhost:8088)" }
if ($missing.Count -gt 0) {
    Write-Host "SKIPPED: Daraja sandbox round-trip not run. Set credentials in backend/.env and callback origin in infra/.env: $($missing -join ', ')."
    Write-Host "Then restart the backend (infra\scripts\up.ps1) so it uses CALLBACK_BASE_URL, and run this again."
    exit 0
}

$api = "http://localhost:$(Get-EnvValue $values 'BACKEND_PORT' '8080')/api/v1"
$login = @{ tenant = "CB"; email = "lab-admin@corebalt.example.test"; password = "lab-admin-password" } | ConvertTo-Json
# Lab admin from infra/lab/seed.sql (local stack only; obvious fake password, role without 2FA).
$session = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType "application/json" -Body $login
if ($session.status -ne "SIGNED_IN") { throw "lab admin sign-in returned $($session.status); load infra/lab/seed.sql first" }
$headers = @{ Authorization = "Bearer $($session.accessToken)" }

function Step([string] $text) { Write-Host "== $text" }

Step "1. Tenant CB: sandbox settings"
$settings = @{ shortcode = $c2bShortcode; shortcodeType = "PAYBILL"; sandbox = $true; consumerKey = $key
               consumerSecret = $secret; passkey = $passkey } | ConvertTo-Json
$view = Invoke-RestMethod -Method Put -Uri "$api/settings/mpesa" -Headers $headers -ContentType "application/json" -Body $settings
Write-Host "   confirmation URL: $($view.callbackUrls.c2bConfirmation -replace '/payments/[^/]+/', '/payments/<token>/')"

Step "2. Register C2B URLs with the sandbox"
Invoke-RestMethod -Method Post -Uri "$api/settings/mpesa/register-urls" -Headers $headers | Out-Null

Step "3. Simulate a C2B payment of KES 2,500 to CB000004 (RESTRICTED in the seed)"
$line = "01900000-0000-7000-8000-0000000b0004"
$before = Invoke-RestMethod -Uri "$api/subscriptions/$line" -Headers $headers
if ($before.accessProfile -ne 'RESTRICTED') { throw 'CB000004 must be restricted before starting the C2B restore check' }
$beforePayments = Invoke-RestMethod -Uri "$api/payments?customerId=01900000-0000-7000-8000-0000000c0004&channel=MPESA_PAYBILL&size=200" -Headers $headers
$beforeIds = @($beforePayments.items | ForEach-Object id)
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${key}:${secret}"))
$token = (Invoke-RestMethod -Uri "https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials" `
        -Headers @{ Authorization = "Basic $basic" }).access_token
$simulate = @{ ShortCode = $c2bShortcode; CommandID = "CustomerPayBillOnline"; Amount = 2500; Msisdn = $testMsisdn
               BillRefNumber = "CB000004" } | ConvertTo-Json
$simulation = Invoke-RestMethod -Method Post -Uri "https://sandbox.safaricom.co.ke/mpesa/c2b/v1/simulate" `
    -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $simulate
if ([string]$simulation.ResponseCode -notmatch '^0+$') { throw 'The C2B simulator did not acknowledge the request' }

$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    $sub = Invoke-RestMethod -Uri "$api/subscriptions/$line" -Headers $headers
    $payments = Invoke-RestMethod -Uri "$api/payments?customerId=01900000-0000-7000-8000-0000000c0004&channel=MPESA_PAYBILL&size=200" -Headers $headers
    $callbackPayment = $payments.items | Where-Object { $_.id -notin $beforeIds -and $_.status -eq 'MATCHED' } | Select-Object -First 1
} until (($sub.accessProfile -eq "FULL" -and $callbackPayment) -or (Get-Date) -gt $deadline)
if ($sub.accessProfile -ne "FULL" -or -not $callbackPayment) { throw "No new matched callback payment restored CB000004 within 3 minutes" }
$entries = Invoke-RestMethod -Uri "$api/customers/01900000-0000-7000-8000-0000000c0004/wallet/entries?size=200" -Headers $headers
if (@($entries.items | Where-Object { $_.type -eq 'PAYMENT' -and $_.referenceId -eq $callbackPayment.id }).Count -ne 1) {
    throw 'The callback payment must have one ledger credit'
}
Write-Host "   restored: $($sub.state) $($sub.accessProfile); ledger: $(($entries.items | ForEach-Object { $_.type }) -join ', ')"
$receipts = Invoke-RestMethod -Uri "$api/receipts?customerId=01900000-0000-7000-8000-0000000c0004&size=200" -Headers $headers
if (@($receipts.items | Where-Object paymentId -eq $callbackPayment.id).Count -ne 1) {
    throw 'The callback payment must have one receipt'
}
$outDir = Join-Path $InfraDir 'lab/out'; New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$result = [ordered]@{checkedAt=(Get-Date).ToString('o');c2bShortcode=$c2bShortcode;c2bPaymentId=$callbackPayment.id;
    c2bProfile=$sub.accessProfile;c2bLedgerCredits=1;c2bReceipts=1;stkStatus='NOT_RUN'}
$result | ConvertTo-Json | Set-Content (Join-Path $outDir 'daraja-sandbox.result.json')
if ($C2bOnly) { Write-Host 'C2B callback, ledger credit, receipt and entitlement restoration passed.'; exit 0 }

Step "4. STK Push KES 1 to the sandbox test number for CB000001"
# The tenant model uses its real merchant shortcode for both APIs. Only the sandbox checker
# switches accounts between completed C2B and STK checks because Safaricom's test codes differ.
$settings = @{ shortcode = $stkShortcode; shortcodeType = "PAYBILL"; sandbox = $true } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$api/settings/mpesa" -Headers $headers -ContentType "application/json" -Body $settings | Out-Null
$push = @{ customerId = "01900000-0000-7000-8000-0000000c0001"; amount = @{ amount = 100; currency = "KES" }; phone = $testMsisdn } | ConvertTo-Json
$stkHeaders = $headers.Clone(); $stkHeaders["Idempotency-Key"] = "sandbox-" + [guid]::NewGuid()
$stk = Invoke-RestMethod -Method Post -Uri "$api/payments/stk" -Headers $stkHeaders -ContentType "application/json" -Body $push
$deadline = (Get-Date).AddMinutes(6)
do {
    Start-Sleep -Seconds 5
    $stk = Invoke-RestMethod -Uri "$api/payments/stk/$($stk.id)" -Headers $headers
} until ($stk.status -ne "PENDING" -or (Get-Date) -gt $deadline)
Write-Host "   STK request: $($stk.status) $($stk.resultCode)"
$result['checkedAt']=(Get-Date).ToString('o'); $result['stkShortcode']=$stkShortcode
$result['stkRequestId']=$stk.id; $result['stkStatus']=$stk.status; $result['stkResultCode']=$stk.resultCode
$result | ConvertTo-Json | Set-Content (Join-Path $outDir 'daraja-sandbox.result.json')
if ($stk.status -ne 'COMPLETED') { throw "The STK sandbox check did not complete ($($stk.status), code $($stk.resultCode))" }

Step "5. CoA to the router"
Write-Host "   This check restores CB000004's entitlement; the CHR payment/reconnect check is separate."
Write-Host "Sandbox round-trip finished."
