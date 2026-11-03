<#
.SYNOPSIS
    Send one payment notification through Celcom using the isolated local lab.
.DESCRIPTION
    Credentials default to backend/.env (or -CredentialsFile). The API key is stored encrypted
    through the lab API and never printed. Requires the API container's SMS allowlist to equal
    CELCOM_TEST_NUMBER. Temporarily uses that number on seeded CB000001, records a fake KES 1
    cash payment, waits for its receipt-specific notification, then restores the customer profile.
    SENT is provider acceptance, not handset delivery. No HTTPS callback is required for sending.
#>
param([string] $CredentialsFile)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lab/scripts/common.ps1')
if (-not $CredentialsFile) { $CredentialsFile = Join-Path $InfraDir '../backend/.env' }
$credentials = Read-DotEnv $CredentialsFile
$local = Read-DotEnv
foreach ($name in @('CELCOM_PARTNER_ID','CELCOM_API_KEY','CELCOM_SENDER_ID','CELCOM_TEST_NUMBER')) {
    if (-not $credentials[$name]) { throw "Set $name in the credentials file before running the check" }
}
$number = $credentials['CELCOM_TEST_NUMBER'].Trim()
if ($number -match '^0[17][0-9]{8}$') { $number = '+254' + $number.Substring(1) }
elseif ($number -match '^254[17][0-9]{8}$') { $number = '+' + $number }
if ($number -notmatch '^\+254[17][0-9]{8}$') { throw 'CELCOM_TEST_NUMBER must be a Kenyan mobile number' }
$env:CELCOM_TEST_ALLOWLIST = $number
Push-Location $InfraDir
try {
    docker compose exec -T -e CELCOM_TEST_ALLOWLIST backend sh -c 'test "$ISP_SMS_ALLOWLIST" = "$CELCOM_TEST_ALLOWLIST"'
    if ($LASTEXITCODE -ne 0) { throw 'Set SMS_ALLOWLIST to the test number in infra/.env and recreate the local backend first' }
} finally { Pop-Location; Remove-Item Env:CELCOM_TEST_ALLOWLIST -ErrorAction SilentlyContinue }

$api = "http://localhost:$(Get-EnvValue $local 'BACKEND_PORT' '8080')/api/v1"
$login = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body (
    @{tenant='CB';email='lab-admin@corebalt.example.test';password='lab-admin-password'} | ConvertTo-Json)
if ($login.status -ne 'SIGNED_IN') { throw 'Seed the local lab before running this check' }
$headers = @{Authorization="Bearer $($login.accessToken)"}
$settings = @{partnerId=$credentials['CELCOM_PARTNER_ID'];apiKey=$credentials['CELCOM_API_KEY'];
    senderId=$credentials['CELCOM_SENDER_ID'];enabled=$true;windowStart='00:00';windowEnd='23:59'}
Invoke-RestMethod -Method Put -Uri "$api/settings/notifications" -Headers $headers -ContentType 'application/json' -Body (
    $settings | ConvertTo-Json) | Out-Null
$customerId = '01900000-0000-7000-8000-0000000c0001'
$customer = Invoke-RestMethod -Uri "$api/customers/$customerId" -Headers $headers
$original = @{}
foreach ($field in @('name','phone','email','address','installationLocation','lat','lng','legacyAccountRef')) { $original[$field]=$customer.$field }
$testProfile = $original.Clone(); $testProfile.phone=$number
$success = $false
try {
    Invoke-RestMethod -Method Put -Uri "$api/customers/$customerId" -Headers $headers -ContentType 'application/json' -Body (
        $testProfile | ConvertTo-Json) | Out-Null
    $key = 'CELCOM-CHECK-' + [guid]::NewGuid()
    $payHeaders = $headers.Clone(); $payHeaders['Idempotency-Key']=$key
    $payment = Invoke-RestMethod -Method Post -Uri "$api/payments/manual" -Headers $payHeaders -ContentType 'application/json' -Body (
        @{customerId=$customerId;amount=@{amount=100;currency='KES'};channel='CASH';reference=$key;
            note='Fake local payment for the Celcom notification check'} | ConvertTo-Json)
    Write-Host 'Fake lab payment recorded; waiting for its Celcom notification (test number only).'
    $message = $null
    $deadline = (Get-Date).AddSeconds(150)
    do {
        Start-Sleep -Seconds 2
        $receipts = Invoke-RestMethod -Uri "$api/receipts?customerId=$customerId&size=50" -Headers $headers -TimeoutSec 10
        $receipt = $receipts.items | Where-Object paymentId -eq $payment.id | Select-Object -First 1
        if ($receipt) {
            $messages = Invoke-RestMethod -Uri "$api/notifications?customerId=$customerId&size=50" -Headers $headers -TimeoutSec 10
            $message = $messages.items | Where-Object { $_.recipient -eq $number -and $_.body.Contains($receipt.number) } | Select-Object -First 1
        }
        if ($message -and $message.status -in @('FAILED','DEAD','SUPPRESSED','QUEUED_RETRY')) {
            throw "Celcom notification was not accepted (status $($message.status)); inspect the local notification screen"
        }
    } until (($message -and $message.status -in @('SENT','DELIVERED')) -or (Get-Date) -gt $deadline)
    if (-not $message -or $message.status -notin @('SENT','DELIVERED')) { throw 'Celcom notification was not accepted within 150 seconds' }
    $result = [ordered]@{completedAt=(Get-Date).ToString('o');provider='CELCOM_AFRICA';
        paymentId=$payment.id;notificationId=$message.id;status=$message.status;
        providerStatus=$message.providerStatus;recipient='configured test number';deliveryConfirmed=($message.status -eq 'DELIVERED')}
    $outDir = Join-Path $InfraDir 'lab/out'
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $result | ConvertTo-Json | Set-Content (Join-Path $outDir 'celcom-check.result.json')
    $result | ConvertTo-Json
    $success = $true
} finally {
    Invoke-RestMethod -Method Put -Uri "$api/customers/$customerId" -Headers $headers -ContentType 'application/json' -Body (
        $original | ConvertTo-Json) | Out-Null
    if (-not $success) {
        $settings.enabled=$false
        Invoke-RestMethod -Method Put -Uri "$api/settings/notifications" -Headers $headers -ContentType 'application/json' -Body (
            $settings | ConvertTo-Json) | Out-Null
    }
}
