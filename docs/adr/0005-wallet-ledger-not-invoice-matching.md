# ADR 0005 · A wallet ledger instead of matching payments to invoices

**Status:** Accepted
**Date:** 2026-10-17

## Context

The original design matched each M-Pesa payment to an outstanding invoice and then needed three configurable rules for partial payments, plus special cases for overpayment, early payment and upgrades. Kenyan subscribers frequently pay round amounts, pay in two instalments, or pay early.

## Decision

Every customer has a wallet backed by an append-only ledger. Payments credit the wallet; renewals, proration and postpaid charges debit it. Invoices and receipts are documents generated from ledger events. A renewal fires automatically when the balance covers the package price; a partial amount simply waits in the wallet (or buys pro-rata days if the tenant chooses). Corrections are new entries (`REVERSAL`, `ADJUSTMENT`), never edits.

## Consequences

- Partial payments, overpayments, early payments and immediate upgrades need no special matching rules.
- Every shilling is traceable to the payment or action that moved it, which is what reconciliation and audits need.
- The running balance is written under a wallet row lock, so concurrent callbacks for the same customer serialise correctly.
- Staff must learn that "unpaid invoice" becomes "negative or insufficient balance"; the dashboard and SMS wording are built around balance and expiry.
- Postpaid still works: the monthly charge is a ledger debit and the invoice is its document.
