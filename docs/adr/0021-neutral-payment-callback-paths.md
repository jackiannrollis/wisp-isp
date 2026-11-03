# ADR 0021 — Neutral payment callback paths

**Status:** Accepted
**Date:** 2026-11-02

## Context

Live Daraja sandbox URL registration returned HTTP 400, code `400.003.02`:
`Bad Request - Invalid ValidationURL - URL has the word MPESA`.
The platform generated `/callbacks/mpesa/<token>/...`, which contract fakes had accepted.
OAuth succeeded; changing credentials would not fix this rejection.

## Decision

Generate `/callbacks/payments/<token>/...` for C2B, STK and transaction-status callbacks.
Keep `/callbacks/mpesa/<token>/...` as an alias so existing registered URLs keep working.
Both prefixes use the same source-address guard, random tenant route token, stored callback
inbox, idempotent processing and audit behaviour. No callback token is changed or exposed.

Daraja rejections reaching the settings API return a stable `payment-provider-error` problem
with HTTP 502 and a validated numeric provider code, instead of a generic internal error.
Raw provider descriptions, request URLs and credentials are not returned.

## Verification

Tests assert neutral generated URLs, source filtering for both prefixes, a working legacy
alias, and safe upstream error details. Live registration is retried through the temporary
HTTPS lab tunnel; a completed round trip must include a new matched callback payment and its
ledger credit, not merely a line that happened to be FULL before the simulation.
