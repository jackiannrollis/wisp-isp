# ADR 0022 — Daraja connection recovery without payment replay

**Status:** Accepted
**Date:** 2026-11-02

## Context

The combined Daraja-to-CHR check initially needed a backend recreation before C2B URL registration
would succeed. Controlled registration-only experiments isolated the behavior on Java 25: the
same token, payload, hostname and HTTP/2 version were used by two retained HTTP clients.
Alternating requests gave three `500.003.1001` failures on one client and three HTTP 200 /
`00000000` successes on the other. The token remained valid. Both HTTP/1.1 and HTTP/2 could
encounter the failure, so changing HTTP versions or rotating credentials is not a fix.

This confirms connection-dependent upstream failure in the tested sandbox route. The precise
Safaricom/CDN backend component cannot be identified from the client. Recreating the application
worked around it by replacing its connection pool, not by changing tenant configuration.

## Decision

On an HTTP 5xx response carrying `500.003.1001`, retire that HTTP client's pool for subsequent
calls. Keep the OAuth cache and normal HTTP negotiation. Do not replay the failed request:
in particular, an ambiguous STK failure must never create a second prompt or payment here.
Existing scheduled queries or an explicit caller retry may use the replacement pool.

Track requests borrowing each pool. A retired client is shut down only after its last in-flight
request completes; concurrent requests are not cancelled. Retirement is atomic, so concurrent
failures from one pool cannot repeatedly replace its successor. The normal `500.001.1001`
STK-query processing response keeps its existing pool.

An API problem for `500.003.1001` tells staff the provider could not complete the request and
to try again; it does not incorrectly tell them to change account settings. Raw provider bodies,
credentials, tokens and callback URLs remain absent from logs and problem details.

## Verification and consequences

Contract tests verify a fresh server connection on explicit retry, the same cached OAuth token,
reuse of healthy connections, no automatic STK replay, unchanged normal pending-query behavior,
and completion of concurrent in-flight work during retirement. An integration test checks safe
provider-unavailable wording. Live confirmation and request IDs are recorded in the lab's
provider-state diagnosis.

This is a recovery mechanism, not a claim that Safaricom's underlying service is fixed. A new
connection may also receive a provider error, which is returned normally. No automatic monetary
retry is introduced, and no production payment was used for diagnosis.
