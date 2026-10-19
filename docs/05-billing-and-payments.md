# 05 · Billing and payments

Every customer has a wallet. Payments credit it, renewals debit it, and an append-only ledger is the single record of money. Invoices and receipts are documents generated from ledger events, not things payments are matched against.

## The ledger

| Rule | Detail |
|---|---|
| Append-only | Entries are never updated or deleted; a mistake is corrected by a `REVERSAL` or `ADJUSTMENT` entry with a reason and an actor |
| Signed amounts | Credits positive (`PAYMENT`, `CREDIT_NOTE`, `REVERSAL` of a charge), charges negative (`RENEWAL_CHARGE`, `PRORATION_CHARGE`, `POSTPAID_CHARGE`, `REFUND`) |
| Running balance | `balance_after_minor` is written with each entry inside the same transaction, under a row lock on the wallet |
| Cached balance | `wallet.balance_minor` mirrors the last `balance_after_minor`; a nightly job recomputes and alerts on any drift |
| Integer minor units | KES cents as `long`; M-Pesa pays whole shillings, so cents are `00`, but the column is future-proof |
| Traceable | Every entry references the payment, renewal or adjustment that caused it |

## Prepaid (default)

1. Payment credits the wallet (`PAYMENT`). The payments module records the payment and writes `PaymentReceived` to the outbox in the same transaction (P4); billing's consumer posts the `PAYMENT` entry, issues the receipt and publishes `WalletCredited`. Billing is the only writer of `PAYMENT` entries, for every channel including manual ones.
2. On `WalletCredited`, each `PENDING` or `ACTIVE` line of the customer (pending lines first, then earliest expiry) is renewed at most once while the balance covers it: `RENEWAL_CHARGE` for the price, `expires_at` extended by the package duration, `SubscriptionRenewed` published. The charge and the new expiry commit in one transaction under the wallet row lock, so one payment never renews two lines. The reference of the charge is the renewal's own ID.
3. Overpayment stays as credit and renews the next cycle automatically when it becomes due: the hourly job renews `ACTIVE` lines on prepaid packages whose expiry is less than `min(24 h, package duration / 4)` away, already-expired ones included (the bounded E4 exception). Reminders for a line whose wallet already covers the next cycle say it will renew automatically instead of asking for payment.
4. Early payment extends from the current expiry; a lapsed line renews from the moment of payment ([04](04-subscription-lifecycle.md)).

Guards: the ledger has a unique (`reference_type`, `reference_id`), so a redelivered event can never credit or charge twice; any `MATCHED` payment older than 5 minutes without its ledger entry raises an alert (a stuck or dead-lettered consumer) and is listed on the reconciliation report.

## Postpaid

- A `POSTPAID_CHARGE` debits the wallet on `billing_day` and an invoice is issued for the period.
- A negative balance is arrears. Restriction starts when arrears outlive the grace period, using the same access logic as prepaid: `expires_at` for postpaid is the invoice due date plus grace.
- Arrears appear on the dashboard and in the customer's portal.

## Partial payments

Partial amounts always land in the wallet. The tenant picks one rule:

| Rule | Behaviour |
|---|---|
| `WAIT_FOR_FULL` (default) | The line stays restricted until the balance covers the full price; the SMS states the remaining amount |
| `PRO_RATA_DAYS` | Days granted = floor(balance ÷ (price ÷ duration_days)); the charge equals the days granted at the daily rate, rounded up to the shilling (one day fewer if that would exceed the balance); the remainder stays in the wallet |

## Package changes

| Change | Timing | Charge |
|---|---|---|
| Upgrade now | Immediate CoA to the new speed | `PRORATION_CHARGE` = (new price − old price) × remaining days ÷ duration days, rounded up to the shilling, the remaining part of the paid cycle measured to the second; expiry unchanged; only before expiry |
| Upgrade at next cycle | At renewal | Full new price at renewal |
| Downgrade | Always at next renewal | Full new price at renewal; no refunds mid-cycle |

An immediate upgrade needs wallet balance ≥ the proration charge; otherwise the portal offers STK Push for the difference and applies the upgrade on payment.

## Payment channels

| Channel | v1 | Notes |
|---|---|---|
| M-Pesa Paybill (C2B) | Yes | Account reference = customer number |
| M-Pesa STK Push | Yes | From the portal and the pay page; amount and account prefilled |
| Cash and bank (manual) | Yes | Recorded by staff with permission `payments.record_manual`, a reference and an audit row |
| Cards, PesaLink | v2 | Through a gateway; same `PaymentReceived` event |
| Trilho | later | Only if Corebalt collects on behalf of tenants (open question) |

## M-Pesa integration

Each tenant connects its own Paybill or Till through a Daraja app whose credentials are stored as tenant secrets. Daraja go-live approval is per tenant and takes time, so it starts on day one of onboarding; staff record payments manually until it completes.

### APIs used

| Daraja API | Use |
|---|---|
| C2B Register URL | Registers the tenant's validation and confirmation callback URLs on the shortcode |
| C2B Validation (optional) | Rejects payments whose account reference matches no customer, before the customer is charged; needs external validation enabled on the shortcode by Safaricom |
| C2B Confirmation | The payment callback: `TransID`, `TransAmount`, `BillRefNumber`, `MSISDN`, `FirstName`, `TransTime` |
| STK Push (Lipa na M-Pesa Online) | Portal and pay-page payments; the callback carries `CheckoutRequestID`, `ResultCode` and the `MpesaReceiptNumber` |
| STK Push Query | Checks an STK request whose callback has not arrived within 2 minutes |
| Transaction Status | Investigates a transaction ID a customer quotes that the platform never received |
| B2C (later) | Refunds to the customer's phone |

### Callback handling

1. The endpoint is per tenant, with a random path token (`/callbacks/mpesa/<token>/...`); requests from outside Safaricom's published IP ranges are rejected. The token is kept as an encrypted tenant secret; the platform-wide route table that finds the tenant holds only its SHA-256. Behind the proxy the source address comes from `X-Forwarded-For`, trusted only from internal proxies, so the backend port is never exposed directly.
2. The raw payload is stored first, even when it is not valid JSON, then processing begins after the response; a failure after storage is retried from the stored copy by a sweeper. A body that cannot be read is kept with its error for investigation.
3. A unique constraint on (`tenant_id`, `provider_txn_id`) makes every payment exist once; a duplicate callback returns success to M-Pesa without side effects. An STK payment to a Paybill also arrives as a C2B confirmation with the same TransID: whichever arrives first creates the payment, the other only links to it.
4. Matching runs in order: exact `BillRefNumber` equals a customer number → normalised reference (trim, upper-case, strip spaces, dots and hyphens, ignore a line suffix such as `-2`, tolerate a missing tenant prefix) → the payer's number when Daraja sends it plain or as a SHA-256 hash of the 2547… form, and it belongs to exactly one customer → otherwise `UNMATCHED`. Current C2B v2 confirmations mask the number (`2547 ***** 126`); a masked number is never matched automatically.
5. The payment and its event commit together: `PaymentReceived` for a matched payment, `PaymentUnmatched` otherwise (P4). Billing credits the ledger from `PaymentReceived`.
6. An unmatched payment lands in the suspense queue with the payer's name, masked number and reference, and, for a masked number, a shortlist of customers whose number fits its visible digits. Staff assign it with one click (permission `payments.assign`, audited); the SMS to the payer says the reference was not recognised and which number to use next time.
7. Daraja expects a fast `ResultCode: 0` acknowledgement; everything beyond storage happens after the response.
8. Validation (C2B Validation API) accepts every payment until a tenant needs otherwise (Q10 default): unknown references go to the suspense queue rather than being refused.

**Identification.** The account reference (the customer number) and STK Push, which already knows the customer, are the only reliable automatic ways to tell who paid: masked numbers cannot be matched. That is why the pay page and every reminder SMS always carry the customer number.

### STK Push

1. The customer taps Pay Now or opens the pay link from an SMS; the amount defaults to the package price or the outstanding difference.
2. The platform calls STK Push with the customer's phone and stores an `stk_request` with the `CheckoutRequestID`.
3. The callback is matched to the request, which already knows the customer; the resulting payment goes through the same ledger path with channel `MPESA_STK`.
4. No callback within 2 minutes → STK Push Query; still pending after 5 minutes → `TIMED_OUT` and the customer is told to pay via Paybill. A `TIMED_OUT` request is never sent again. Only a `PENDING` request changes state, under a row lock, so a callback that arrives while a query is in flight wins. A success callback that arrives after `TIMED_OUT` is still credited: the money was taken.
5. Result codes are kept on the request: `0` success, `1032` cancelled by the customer, `1037` phone unreachable, `2001` wrong PIN, and others as Daraja sends them.

### Reconciliation

- Daily per tenant: compare received callbacks against the M-Pesa statement (CSV export from the M-Pesa organisation portal, uploaded by the tenant or fetched where available). Transactions on the statement but not in the platform are investigated with Transaction Status, whose result becomes a payment through the usual callback path; the report lists both directions, plus matched payments that never reached the ledger (older than 5 minutes).
- Statement columns are found by name: `Receipt No.` and `Paid In` are required; `Completion Time`, `Transaction Status` and `Details` are used when present. Only completed money in counts.
- Alert when a tenant with normal volume receives no callbacks for 2 hours during the day, which usually means the callback URL registration was lost.

### Sandbox round-trip

Request shapes are pinned by contract tests against a fake Daraja; the sandbox only has to confirm Safaricom's side. To run it:

1. Create an app on developer.safaricom.co.ke with the Lipa na M-Pesa Online and M-Pesa Sandbox products; note the consumer key and secret and the sandbox passkey.
2. Start a tunnel to Caddy, for example `cloudflared tunnel --url http://localhost:8088`, and note its https URL.
3. In `infra/.env` set `CALLBACK_BASE_URL` (the tunnel URL), `DARAJA_SANDBOX_CONSUMER_KEY`, `DARAJA_SANDBOX_CONSUMER_SECRET` and `DARAJA_SANDBOX_PASSKEY`; restart the stack and load the lab fixtures (`infra\scripts\up.ps1`, `infra\lab\scripts\seed.ps1`).
4. Run `infra\scripts\daraja-sandbox.ps1` (or `.sh`). It configures tenant CB for the sandbox, registers the C2B URLs, simulates a C2B payment to CB000004 and waits until the ledger has credited it and the line is FULL, then sends an STK Push to the sandbox test number and waits for its result.
5. The last step, the CoA to the router, is reported as skipped until the network module's CoA consumer exists; until then the 24-hour Session-Timeout cap bounds the delay.

If a sandbox callback is refused, the backend logs `Callback refused from <ip>`: Safaricom's sandbox may call from addresses outside the published production list; add it with `ISP_MPESA_CALLBACKALLOWEDCIDRS` for the lab only.

## Receipts and invoices

- A receipt is issued by billing for every credited payment with a per-tenant sequential number that is never reused (`CB-R000123`); it is available in the portal as a PDF and referenced in the `PAYMENT_RECEIVED` SMS.
- Postpaid invoices are issued per billing period; prepaid customers can download a statement of ledger entries for any period.
- Numbering and content are designed so KRA eTIMS can be added in v3 without renumbering.

## Credit notes and refunds (v2)

- A `CREDIT_NOTE` credits the wallet for a service failure or billing error; it requires the `billing.credit` permission and a reason, and amounts above a tenant threshold need a second approval.
- A `REFUND` pays wallet balance out (B2C or manual) and debits the wallet; the same approval rules apply.

## Agent commissions (v3)

A `COMMISSION` entry on the agent's own wallet records the agent's share of each sale (for example KES 100 of a KES 1,500 subscription). Payouts to agents follow the refund path.
