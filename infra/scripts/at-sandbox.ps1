# Africa's Talking sandbox round-trip (docs/04, "SMS sandbox round-trip"). Runs only when infra/.env
# has AT_SANDBOX_API_KEY, AT_SANDBOX_SENDER_ID and AT_SANDBOX_TEST_NUMBER (a number open in the AT
# simulator) and an https CALLBACK_BASE_URL for delivery reports; otherwise it says what is missing
# and exits 0. The stack must be up and seeded.
#
# Steps: configure tenant CB's SMS settings (sandbox), point customer CB000001 at the simulator
# number, record a cash payment for it, and wait until its PAYMENT_RECEIVED message is SENT and,
# once Africa's Talking calls back, DELIVERED. Secrets stay in this process.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\lab\scripts\common.ps1")
$values = Read-DotEnv

$key = Get-EnvValue $values "AT_SANDBOX_API_KEY" ""
$sender = Get-EnvValue $values "AT_SANDBOX_SENDER_ID" ""
$testNumber = Get-EnvValue $values "AT_SANDBOX_TEST_NUMBER" ""
$base = Get-EnvValue $values "CALLBACK_BASE_URL" ""
$missing = @()
if (-not $key) { $missing += "AT_SANDBOX_API_KEY" }
if (-not $sender) { $missing += "AT_SANDBOX_SENDER_ID" }
if ($testNumber -notmatch '^\+254[17][0-9]{8}$') { $missing += "AT_SANDBOX_TEST_NUMBER (E.164, open in the AT simulator)" }
if (-not $base.StartsWith("https://")) { $missing += "CALLBACK_BASE_URL (https tunnel to http://localhost:8088)" }
if ($missing.Count -gt 0) {
    Write-Host "SKIPPED: Africa's Talking sandbox round-trip not run. Set in infra/.env: $($missing -join ', ')."
    exit 0
}

$api = "http://localhost:$(Get-EnvValue $values 'BACKEND_PORT' '8080')/api/v1"
$login = @{ tenant = "CB"; email = "lab-admin@corebalt.example.test"; password = "lab-admin-password" } | ConvertTo-Json
# Lab admin from infra/lab/seed.sql (local stack only; obvious fake password, role without 2FA).
$session = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType "application/json" -Body $login
if ($session.status -ne "SIGNED_IN") { throw "lab admin sign-in returned $($session.status); load infra/lab/seed.sql first" }
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
$customer = "01900000-0000-7000-8000-0000000c0001"

Write-Host "== 1. Tenant CB: Africa's Talking sandbox settings"
$settings = @{ atUsername = "sandbox"; senderId = $sender; sandbox = $true; apiKey = $key; windowStart = "00:00"; windowEnd = "23:59" } | ConvertTo-Json
$view = Invoke-RestMethod -Method Put -Uri "$api/settings/notifications" -Headers $headers -ContentType "application/json" -Body $settings
Write-Host "   Set the delivery report URL in the AT sandbox dashboard to: $($view.deliveryReportUrl -replace '/sms/[^/]+/', '/sms/<token>/')"

Write-Host "== 2. CB000001 uses the simulator number"
$c = Invoke-RestMethod -Uri "$api/customers/$customer" -Headers $headers
$profile = @{ name = $c.name; phone = $testNumber } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$api/customers/$customer" -Headers $headers -ContentType "application/json" -Body $profile | Out-Null

Write-Host "== 3. A cash payment for CB000001, which renews its line and sends one SMS"
$payHeaders = $headers.Clone(); $payHeaders["Idempotency-Key"] = "at-sandbox-" + [guid]::NewGuid()
$pay = @{ customerId = $customer; amount = @{ amount = 250000; currency = "KES" }; channel = "CASH"; reference = "ATSBX-" + (Get-Random) } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/payments/manual" -Headers $payHeaders -ContentType "application/json" -Body $pay | Out-Null

$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    $sms = (Invoke-RestMethod -Uri "$api/notifications?customerId=$customer&size=1" -Headers $headers).items | Select-Object -First 1
} until (($sms -and $sms.status -in @("DELIVERED", "FAILED", "DEAD")) -or (Get-Date) -gt $deadline)
Write-Host "   $($sms.templateKey): $($sms.status) $($sms.providerStatus) $($sms.failureReason)"
Write-Host "   text: $($sms.body)"
