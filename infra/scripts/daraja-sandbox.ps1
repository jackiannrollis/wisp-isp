# Daraja sandbox round-trip (docs/05, "Sandbox round-trip"). Runs only when infra/.env has
# DARAJA_SANDBOX_CONSUMER_KEY, DARAJA_SANDBOX_CONSUMER_SECRET, DARAJA_SANDBOX_PASSKEY and an https
# CALLBACK_BASE_URL (a tunnel to Caddy :8088), and the stack is up and seeded; otherwise it says
# what is missing and exits 0.
#
# Steps: configure tenant CB's M-Pesa settings (sandbox), register the C2B URLs, simulate a C2B
# payment for CB000004 and wait for it to be credited and the line renewed, then STK Push the
# sandbox test number for CB000001 and wait for the result. Secrets stay in this process; they are
# never put on a command line or printed.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\lab\scripts\common.ps1")
$values = Read-DotEnv

$key = Get-EnvValue $values "DARAJA_SANDBOX_CONSUMER_KEY" ""
$secret = Get-EnvValue $values "DARAJA_SANDBOX_CONSUMER_SECRET" ""
$passkey = Get-EnvValue $values "DARAJA_SANDBOX_PASSKEY" ""
$base = Get-EnvValue $values "CALLBACK_BASE_URL" ""
$shortcode = Get-EnvValue $values "DARAJA_SANDBOX_SHORTCODE" "174379"
$testMsisdn = Get-EnvValue $values "DARAJA_SANDBOX_TEST_MSISDN" "254708374149"

$missing = @()
if (-not $key) { $missing += "DARAJA_SANDBOX_CONSUMER_KEY" }
if (-not $secret) { $missing += "DARAJA_SANDBOX_CONSUMER_SECRET" }
if (-not $passkey) { $missing += "DARAJA_SANDBOX_PASSKEY" }
if (-not $base.StartsWith("https://")) { $missing += "CALLBACK_BASE_URL (https tunnel to http://localhost:8088)" }
if ($missing.Count -gt 0) {
    Write-Host "SKIPPED: Daraja sandbox round-trip not run. Set in infra/.env: $($missing -join ', ')."
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
$settings = @{ shortcode = $shortcode; shortcodeType = "PAYBILL"; sandbox = $true; consumerKey = $key
               consumerSecret = $secret; passkey = $passkey } | ConvertTo-Json
$view = Invoke-RestMethod -Method Put -Uri "$api/settings/mpesa" -Headers $headers -ContentType "application/json" -Body $settings
Write-Host "   confirmation URL: $($view.callbackUrls.c2bConfirmation -replace '/mpesa/[^/]+/', '/mpesa/<token>/')"

Step "2. Register C2B URLs with the sandbox"
Invoke-RestMethod -Method Post -Uri "$api/settings/mpesa/register-urls" -Headers $headers | Out-Null

Step "3. Simulate a C2B payment of KES 2,500 to CB000004 (RESTRICTED in the seed)"
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${key}:${secret}"))
$token = (Invoke-RestMethod -Uri "https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials" `
        -Headers @{ Authorization = "Basic $basic" }).access_token
$simulate = @{ ShortCode = $shortcode; CommandID = "CustomerPayBillOnline"; Amount = 2500; Msisdn = $testMsisdn
               BillRefNumber = "CB000004" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "https://sandbox.safaricom.co.ke/mpesa/c2b/v1/simulate" `
    -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $simulate | Out-Null

$line = "01900000-0000-7000-8000-0000000b0004"
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    $sub = Invoke-RestMethod -Uri "$api/subscriptions/$line" -Headers $headers
} until ($sub.accessProfile -eq "FULL" -or (Get-Date) -gt $deadline)
if ($sub.accessProfile -ne "FULL") { throw "CB000004 was not restored within 3 minutes; check the callback rows and backend logs" }
$entries = Invoke-RestMethod -Uri "$api/customers/01900000-0000-7000-8000-0000000c0004/wallet/entries" -Headers $headers
Write-Host "   restored: $($sub.state) $($sub.accessProfile); ledger: $(($entries.items | ForEach-Object { $_.type }) -join ', ')"

Step "4. STK Push KES 1 to the sandbox test number for CB000001"
$push = @{ customerId = "01900000-0000-7000-8000-0000000c0001"; amount = @{ amount = 100; currency = "KES" }; phone = $testMsisdn } | ConvertTo-Json
$stkHeaders = $headers.Clone(); $stkHeaders["Idempotency-Key"] = "sandbox-" + [guid]::NewGuid()
$stk = Invoke-RestMethod -Method Post -Uri "$api/payments/stk" -Headers $stkHeaders -ContentType "application/json" -Body $push
$deadline = (Get-Date).AddMinutes(6)
do {
    Start-Sleep -Seconds 5
    $stk = Invoke-RestMethod -Uri "$api/payments/stk/$($stk.id)" -Headers $headers
} until ($stk.status -ne "PENDING" -or (Get-Date) -gt $deadline)
Write-Host "   STK request: $($stk.status) $($stk.resultCode) $($stk.resultDesc)"

Step "5. CoA to the router"
Write-Host "   SKIPPED: no live session on a real router here; CoA is proven by CoaWorkerIT and the network lab."
Write-Host "Sandbox round-trip finished."
