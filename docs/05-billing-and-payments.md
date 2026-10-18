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

1. Payment credits the wallet (`PAYMENT`).
2. If balance ≥ package price and the line is `PENDING`, `ACTIVE` or `RESTRICTED`, a renewal fires automatically: `RENEWAL_CHARGE` for the price, `expires_at` extended by the package duration, `SubscriptionRenewed` published.
3. Overpayment stays as credit and renews the next cycle automatically when it becomes due.
4. Early payment extends from the current expiry; a lapsed line renews from the moment of payment ([04](04-subscription-lifecycle.md)).

## Postpaid

- A `POSTPAID_CHARGE` debits the wallet on `billing_day` and an invoice is issued for the period.
- A negative balance is arrears. Restriction starts when arrears outlive the grace period, using the same access logic as prepaid: `expires_at` for postpaid is the invoice due date plus grace.
- Arrears appear on the dashboard and in the customer's portal.

## Partial payments

Partial amounts always land in the wallet. The tenant picks one rule:

| Rule | Behaviour |
|---|---|
| `WAIT_FOR_FULL` (default) | The line stays restricted until the balance covers the full price; the SMS states the remaining amount |
| `PRO_RATA_DAYS` | Days granted = floor(balance ÷ (price ÷ duration_days)); the charge equals the days granted at the daily rate; the remainder stays in the wallet |

## Package changes

| Change | Timing | Charge |
|---|---|---|
| Upgrade now | Immediate CoA to the new speed | `PRORATION_CHARGE` = (new price − old price) × remaining days ÷ duration days, rounded up to the shilling; expiry unchanged |
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

1. The endpoint is per tenant, with a random path token; requests from outside Safaricom's published IP ranges are rejected.
2. The raw payload is stored first, then processing begins; a failure after storage is retried from the stored copy.
3. A unique constraint on (`tenant_id`, `provider_txn_id`) makes every callback process exactly once; a duplicate returns success to M-Pesa without side effects.
4. Matching runs in order: exact `BillRefNumber` equals a customer number → normalised reference (trim, upper-case, strip spaces and hyphens, tolerate a missing tenant prefix) → `MSISDN` matches exactly one customer phone → otherwise `UNMATCHED`.
5. A matched payment publishes `PaymentReceived` in the same transaction as the ledger credit (via the outbox).
6. An unmatched payment lands in the suspense queue with the payer's name and phone; staff assign it with one click, and the SMS to the payer says the reference was not recognised and which number to use next time.
7. Daraja expects a fast `ResultCode: 0` acknowledgement; everything beyond storage happens after the response.

### STK Push

1. The customer taps Pay Now or opens the pay link from an SMS; the amount defaults to the package price or the outstanding difference.
2. The platform calls STK Push with the customer's phone and stores an `stk_request` with the `CheckoutRequestID`.
3. The callback is matched to the request, which already knows the customer; the resulting payment goes through the same ledger path with channel `MPESA_STK`.
4. No callback within 2 minutes → STK Push Query; still pending after 5 minutes → `TIMED_OUT` and the customer is told to pay via Paybill.

### Reconciliation

- Daily per tenant: compare received callbacks against the M-Pesa statement (CSV export from the M-Pesa organisation portal, uploaded by the tenant or fetched where available). Transactions on the statement but not in the platform are investigated with Transaction Status and recorded; the report lists both directions.
- Alert when a tenant with normal volume receives no callbacks for 2 hours during the day, which usually means the callback URL registration was lost.

## Receipts and invoices

- A receipt is issued for every matched payment with a per-tenant sequential number that is never reused; it is available in the portal as a PDF and referenced in the `PAYMENT_RECEIVED` SMS.
- Postpaid invoices are issued per billing period; prepaid customers can download a statement of ledger entries for any period.
- Numbering and content are designed so KRA eTIMS can be added in v3 without renumbering.

## Credit notes and refunds (v2)

- A `CREDIT_NOTE` credits the wallet for a service failure or billing error; it requires the `billing.credit` permission and a reason, and amounts above a tenant threshold need a second approval.
- A `REFUND` pays wallet balance out (B2C or manual) and debits the wallet; the same approval rules apply.

## Agent commissions (v3)

A `COMMISSION` entry on the agent's own wallet records the agent's share of each sale (for example KES 100 of a KES 1,500 subscription). Payouts to agents follow the refund path.
