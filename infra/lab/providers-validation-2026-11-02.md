# Provider validation — 2026-11-02

**Follow-up confirmed on 2026-11-03:** the provider-state failure was connection-dependent, with
the same valid token working on another retained connection. Pool recovery passed live on Java 25
and all 464 backend tests passed. See [the diagnosis](daraja-state-validation-2026-11-03.md).

**Combined test (2026-11-02, 22:28 EAT): Daraja callback-to-CHR restoration passed.** FULL access
was observed in 4.357 seconds from the callback; the platform recorded 3.328 seconds. See
[`daraja-restore-validation-2026-11-02.md`](daraja-restore-validation-2026-11-02.md), including the
provider-state reuse issue observed before payment. The follow-up above resolves that diagnosis.

C2B and STK sandbox payments passed earlier. The C2B checker now separates
the C2B `600000` and Express `174379` test shortcodes. The successful KES 1 STK retry produced a
processed callback, one matched payment, one wallet credit and one receipt. Earlier failures below
are historical. Production go-live and physical deployment remain separate.

## Celcom Africa

Credentials were read from the ignored `backend/.env`, not copied into source or Docker images.
The local `corebalt-isp` API's allowlist was restricted to the configured test number before the
account was connected. The separate browser app and console database were preserved.

A fake KES 1 cash payment through the lab API generated the payment notification. Celcom
accepted it, and the user confirmed receiving the text about KES 1. The lab customer profile
was restored afterwards. The resulting notification ID is
`01a19d3b-d324-7d98-b18f-ebfd77f1fd09`; no recipient number or credential is recorded here.

A subsequent `getdlr` query returned response-code 200, the requested message-id,
delivery-status 32, DeliveredToTerminal, and a delivery turnaround of 00:00:03.
The application poller then independently changed the same known Celcom notification to
DELIVERED at `2026-11-02T17:34:44.969401Z`, without sending another SMS. This established the
response contract used by ADR 0020. Unknown statuses remain unconfirmed; failed-delivery
codes have not been inferred from other providers' documentation.

The existing test message predates the per-message provider discriminator. Only that already
verified message was labelled CELCOM_AFRICA for this lookup test. Other historical rows remain
legacy and are not polled using Celcom credentials. New sends label themselves in the sending
transaction.

## Daraja sandbox

OAuth accepted the supplied sandbox consumer credentials. A temporary Cloudflare tunnel was
started to the isolated lab Caddy for callbacks. The first registration attempt exposed the
neutral-URL issue documented in ADR 0021: HTTP 400 / 400.003.02, rejecting the word MPESA in
the validation URL. This was a real provider rejection; the original API incorrectly surfaced
it as an internal 500. Both the generated URLs and safe upstream error handling were corrected.

The neutral-path retry passed the URL-format check but registration returned HTTP 500 /
`500.003.1001`: "Service is currently unreachable. Please try again later." The application
returned a safe 502 problem with the provider code instead of its previous generic internal 500.

An independent KES 1 STK request to the configured sandbox test number was accepted (HTTP 202),
request `01a19d6a-3714-72d0-a514-156d92e2af3f`. The application's scheduled status query subsequently
recorded FAILED / `1037`. A read-only database check confirmed that it was queried and that zero
STK callbacks matched its checkout ID. Acceptance therefore does not establish payment success
or callback reachability. C2B credit/renewal and a successful STK callback remain unverified.

The temporary tunnel was stopped after these checks, and the development callback origin was
restored to `http://localhost:8088`. A fresh HTTPS tunnel and backend restart are needed for a retry.
This document does not claim Gate 1, production Daraja go-live, or a physical ISP deployment.

## Verification and local rollout

Backend `mvnw.cmd -B verify`: 175 unit tests and 284 integration tests passed. Frontend lint,
all three production builds and 123 tests passed; the final generated API types passed the
format check. Backend, worker and web images were updated in the existing browser stack without
resetting accounts or data. Both API instances and the worker were healthy; the admin, console
and customer portal routes each returned HTTP 200 over local TLS.

## Requested retry, 21:25 EAT

Retried with the existing credentials in `backend/.env`, a fresh HTTPS tunnel, and the existing
sandbox test shortcode/phone defaults. The public tunnel's health endpoint returned HTTP 200.
C2B registration again returned `500.003.1001` (application HTTP 502, correlation ID
`04b85e41-ff18-4943-ba87-61905ddd7c76`). This response does not establish whether the cause is
provider availability or the C2B sandbox configuration.

The independent KES 1 STK retry was accepted as request
`01a19d81-b317-72ea-ba4a-41e31c316872` and ended FAILED / `1032` (cancelled), rather than the
earlier `1037`. A read-only database check confirmed one matching STK callback and one processed
callback; `queried_at` was also set. This proves callback arrival and processing for the failure
result. It does not prove a successful payment, wallet credit or renewal. The temporary tunnel
was stopped and the development callback origin restored after recording the result.

## C2B diagnosis and resolution, 21:39 EAT

Controlled requests went directly to Safaricom's sandbox with the supplied consumer credentials,
identical neutral callback URLs and `ResponseType: Completed`. No application code or credentials
changed between these comparisons:

| Shortcode encoding | Endpoint | HTTP | Provider result |
|---|---|---|---|
| `"174379"` | v2/registerurl | 500 | `500.003.1001`, service unreachable |
| `"174379"` | v1/registerurl | 500 | `500.003.1001`, service unreachable |
| numeric `174379` | v2/registerurl | 400 | `400.003.02`, invalid shortcode |
| numeric `174379` | v1/registerurl | 400 | `400.003.02`, invalid shortcode |
| `"600000"` | v2/registerurl | 200 | `00000000` |
| `"600000"` | v1/registerurl | 200 | `00000000` |

This isolates the apparent service-unavailable failure to the incorrect C2B sandbox shortcode.
Changing the endpoint version was unnecessary. Numeric encoding also fails request validation;
the checker and application continue to send the shortcode as a string. The same credentials
authenticated all requests, so no extra initiator password was needed for C2B registration.

The corrected checker keeps the C2B and STK test shortcodes separate. Legacy
`DARAJA_SANDBOX_SHORTCODE` is an STK-only alias. `-C2bOnly` verifies C2B independently, preserves
its result and leaves CB configured with the C2B sandbox shortcode. Full runs switch to the STK
shortcode after C2B completes and save C2B evidence even if STK later fails.

`pwsh -NoProfile -File infra/scripts/daraja-sandbox.ps1 -C2bOnly` passed through the application's
unchanged v2 registration client. The live simulator sent a KES 2,500 payment to CB000004:

- Payment ID `01a19d8e-ceee-7a44-8222-6e27de4c1146`, MATCHED, amount `250000` minor units.
- One processed C2B confirmation linked to that exact payment (read-only database verification).
- Exactly one ledger PAYMENT credit referencing the payment and exactly one receipt (API checks).
- CB000004 changed from RESTRICTED to ACTIVE / FULL.

The check finished at `2026-11-02T21:39:08.5930474+03:00`. Its sanitized machine-readable proof is
in ignored `infra/lab/out/daraja-sandbox.result.json`. The modified checker passed PowerShell syntax
and whitespace checks; no backend application code changed in this diagnosis. The diagnostic
tunnel was closed and the local callback origin restored afterwards. This proves a C2B sandbox
payment and entitlement renewal, not an STK success, a live-money payment or a CHR restoration.

## KES 1 STK test on the requested handset, 21:47 EAT

The user requested a KES 1 STK test on the number ending 0861. The test used the Daraja sandbox,
Express shortcode `174379` and a fresh public HTTPS callback tunnel. No full phone number or
credential is recorded here.

The first request, `01a19d95-2366-7af6-9401-4b1c9655bedd`, ended FAILED / `1037`. The user confirmed
that the prompt reached the phone but the handset hung, and explicitly requested a retry. The
first request had no linked payment when checked after the retry.

The requested retry, `01a19d96-2414-7c99-9fc2-25aa320fb648`, was accepted at
`2026-11-02T18:47:05.7165872Z` and reported COMPLETED / `0` at
`2026-11-02T18:47:15.7843371Z`. Read-only database verification found:

- One processed STK callback matching this request's checkout ID.
- Linked payment `01a19d96-4bf9-76f9-9015-19b0d5218aae`, MATCHED, amount `100` minor units (KES 1).
- Exactly one PAYMENT ledger credit referencing this payment and exactly one receipt.

The isolated fixture customer CB000001 received the payment. This verifies the sandbox STK
payment, callback, matching, wallet and receipt flow; KES 1 does not establish a full-price
subscription renewal. Sanitized evidence is in ignored `infra/lab/out/daraja-phone.result.json`
and `daraja-phone-attempt-1.result.json`. The previous sandbox shortcode settings and local
callback origin were restored, and the temporary tunnel was closed after verification.
