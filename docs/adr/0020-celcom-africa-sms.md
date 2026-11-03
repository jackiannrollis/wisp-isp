# ADR 0020 — Celcom Africa for SMS

**Status:** Accepted; sending and delivery lookup implemented
**Date:** 2026-11-02

## Context

The user selected Celcom Africa instead of Africa's Talking for messaging. This applies to
queued notifications, portal sign-in codes and staff temporary-password messages.

## Decision

Use the tenant's Celcom partner ID, API key and approved sender ID. The client uses JSON POST
to the documented send endpoint, with credentials in the body rather than the URL. E.164
recipients are sent as international digits. A successful acknowledgement must identify the
requested recipient and include a message ID. The API's documented `respose-code` spelling and
its corrected spelling are both read. See [Celcom's API documentation](https://www.celcomafrica.com/developers).

200 is accepted for sending, not delivered. 1003 flags an invalid number; 1001 (sender), 1004
(credit) and 1006 (credentials) hold the message and raise tenant alerts. Other failures retain
the bounded retry policy. Raw provider descriptions never enter logs or stored failure reasons:
they may echo credentials, recipients or message text.

Keep encryption, tenant isolation, reminder windows, event deduplication, permission checks,
the environment allowlist and masking of sign-in secrets. Celcom keys use the new
`CELCOM_SMS_API_KEY` secret kind; old provider keys are never reused. Old settings need an
explicit Celcom connection. The migration adds columns and preserves historical columns for
the previous release. No old provider client remains in the sending path.

The public documentation supplies the delivery-lookup request but no response schema. The live test on 2026-11-02 confirmed response-code 200, the matching message-id, delivery-status 32 and DeliveredToTerminal. Celcom settings expose no legacy callback URL, and legacy SMS callbacks
cannot update messages for a Celcom route. Historical legacy reports retain their own route.
The API polls explicitly Celcom-labelled SENT messages for up to 48 hours, 50 per batch with a two-minute lookup lease. Only the verified matching 32/DeliveredToTerminal report marks DELIVERED. Unknown status codes remain SENT; no failed-delivery code is guessed and no lookup resends a message.

## Verification

Contract tests cover JSON, recipient correlation, numeric IDs, malformed acknowledgements,
provider errors and secret-safe diagnostics. Integration tests cover payment/reminder sends,
OTP/password masking, retries, permissions, legacy migration and callback isolation.

`infra/scripts/celcom-check.ps1` loads credentials from `backend/.env` by default, requires
the running development API's allowlist to equal the test number, and sends a payment-specific
notification via a fake local payment. It restores the customer profile and disables the
account on failure. Live acceptance and provider delivery were verified on 2026-11-02, and the user confirmed receiving the KES 1 notification. Do not rerun the stack-test recipe to refresh the UI: it resets
the console administrator; update the local containers in place instead.
