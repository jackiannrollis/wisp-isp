# Daraja callback to CHR restoration — 2026-11-02

**Passed:** a genuine Daraja sandbox C2B callback automatically restored the isolated CHR
customer in 4.357 seconds as observed on RouterOS. The platform independently recorded a
CUT_OFF / NEW_SESSION restoration in 3.327682 seconds. No manual reconnect occurred after payment.

## Setup and result

The existing `corebalt-isp` development stack and two CHR VMs were used. The browser app's separate
database and console accounts were preserved. Credentials came from ignored `backend/.env`.
CB000001 was expired by ten days, then received one preparation-only RADIUS Disconnect so it
redialled into RESTRICTED before the payment test started.

| Observation | Result |
|---|---|
| Before payment | RESTRICTED / EXPIRED, session `0x81000023`, address `10.11.0.250`, queue `512000/512000` |
| Restricted connectivity | DNS succeeded; HTTP reached the pay page; HTTPS blocked; ping 0/3 |
| Wallet before payment | KES 2 |
| Sandbox C2B shortcode | `600000` |
| Simulated payment | KES 2,498; covers the KES 2,500 renewal with the existing balance |
| Payment ID | `01a19dbb-c3d7-7640-9354-b0a93c41d254` |
| Stored callback | `2026-11-02T19:28:10.672318Z`; one processed C2B confirmation |
| Renewal | `2026-11-02T19:28:12.373648Z` |
| Platform restoration | `2026-11-02T19:28:14Z`; CUT_OFF / NEW_SESSION; 3.327682 seconds from callback |
| Router observed FULL | `2026-11-02T19:28:15.0295226Z`; 4.357 seconds from callback |
| After payment | FULL, new session `0x81000024`, address `10.10.0.248`, queue `5000000/10000000` |
| Financial checks | One PAYMENT credit, one full-price RENEWAL_CHARGE, one receipt; final wallet zero |
| Manual reconnects after payment | Zero; the test issued no post-payment Disconnect or expiry edit |
| Restored connectivity | Real HTTP site, HTTPS reachable, DNS succeeded, ping 3/3 |

The observer's timing includes the delay until its polling saw the restored router session. The
platform's time comes from its existing accounting/event reporting; the test only read that data.
Reporting's scheduled completion ran later and was not included as restoration delay.

## Reproduce

Start a public HTTPS tunnel to the isolated Caddy at `http://localhost:8088`, set `CALLBACK_BASE_URL`
in `infra/.env`, then recreate the backend with that environment. Keep provider credentials in
`backend/.env`. On an existing seeded lab, prepare and run:

```powershell
pwsh -NoProfile -File infra/lab/scripts/lab.ps1 -Command set-line -Arg1 CB000001 -Arg2 '-10 days' -RouterAddress 192.168.207.224 -ClientAddress 192.168.194.168
pwsh -NoProfile -File infra/lab/scripts/lab.ps1 disconnect CB000001 -RouterAddress 192.168.207.224 -ClientAddress 192.168.194.168
pwsh -NoProfile -File infra/lab/scripts/lab.ps1 check -RouterAddress 192.168.207.224 -ClientAddress 192.168.194.168
pwsh -NoProfile -File infra/lab/scripts/daraja-restore.ps1 -RouterAddress 192.168.207.224
pwsh -NoProfile -File infra/lab/scripts/lab.ps1 check -RouterAddress 192.168.207.224 -ClientAddress 192.168.194.168
```

The checker verifies accounting sees a live restricted session before payment. It calculates the
whole-shilling shortfall, uses the sandbox simulator rather than a real handset request, checks
the exact new callback payment, and reads the platform's reporting row. Proof is saved in ignored
`infra/lab/out/daraja-restore.result.json`. PowerShell syntax and whitespace checks passed; no
backend application code changed for this check.

## Follow-up and limits

Before any payment was submitted, two URL registrations through the app returned `500.003.1001`
while a direct request with the same shortcode and callback URLs returned `00000000`. Recreating
the isolated backend to clear its cached provider state allowed the unchanged app client to
register successfully and the complete test to pass. The exact token/connection cause is not
established at that point. The subsequent
[provider-state diagnosis](daraja-state-validation-2026-11-03.md) confirmed connection-dependent
upstream failures with a valid unchanged token. ADR 0022's pool recovery was then verified live
and in the full backend suite; backend restarts are no longer needed to replace the failed pool.

This is one sandbox observation using virtual CHR, not completion of Gate 1's two production
billing cycles. Physical ISP hardware, production Daraja go-live, staging and node B remain
separate. Queue speeds were verified as configuration; the free CHR licence limits throughput.
The temporary callback tunnel was closed and the local callback origin restored after verification.
The fixture customer remains renewed and connected in FULL; the lab was not reseeded.
