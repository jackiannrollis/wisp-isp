# Daraja provider-state diagnosis and recovery — 2026-11-03

**Confirmed:** the failure was connection-dependent upstream behavior, not an expired cached
OAuth token. The client now retires the failed HTTP pool for the next attempt, without replaying
the failed request or interrupting other in-flight work. Full backend verification passed:
179 unit tests and 285 integration tests. Live recovery passed on Java 25.

## Controlled diagnosis

All diagnostic calls were sandbox C2B URL registrations with string shortcode `600000`,
`ResponseType: Completed`, and the same neutral HTTPS callback URLs. No C2B simulation, STK
prompt or money-moving request was sent. Tokens and credentials stayed in memory and ignored
`backend/.env`; only sanitized results were saved.

An independently generated second token did not invalidate the first: the first still registered
successfully afterwards. Java 25 comparisons then reproduced failures on both HTTP/1.1 and
HTTP/2, including fresh tokens/connections. HTTP version alone therefore was not the cause.

The decisive comparison used one unchanged token and payload, the same Java 25 runtime, HTTP/2
and resolved destination `45.223.20.17`. Two HTTP clients were retained and requests alternated:

| UTC time, 2026-11-02 | Retained client | Result |
|---|---|---|
| 20:04:18.894 | Failing | HTTP 500 / `500.003.1001` |
| 20:04:24.858 | Successful | HTTP 200 / `00000000` |
| 20:04:30.099 | Failing | HTTP 500 / `500.003.1001` |
| 20:04:35.908 | Successful | HTTP 200 / `00000000` |
| 20:04:41.058 | Failing | HTTP 500 / `500.003.1001` |
| 20:04:46.767 | Successful | HTTP 200 / `00000000` |

The provider described the failures as service unreachable. Its failure request IDs were
`135b-4679-8ea6-1c3dca88c6a4839917`, `135b-4679-8ea6-1c3dca88c6a4839929` and
`135b-4679-8ea6-1c3dca88c6a4839935`. The precise Safaricom/CDN component remains outside client
visibility. These results establish connection-dependent failure; they do not identify a
particular internal server. Restarting the backend replaced its pool and masked this behavior.

Sanitized diagnostic output is in ignored `infra/lab/out/daraja-client-pair-True.result.jsonl`.
The earlier HTTP-version and token comparisons are preserved separately; they are not omitted
from the conclusion in favor of the first plausible explanation.

## Recovery and live confirmation

[ADR 0022](../../docs/adr/0022-daraja-connection-recovery.md) documents the change:

- A 5xx response with `500.003.1001` retires its pool for subsequent requests.
- The valid OAuth token remains cached. HTTP negotiation is unchanged.
- The original failed call returns its error; STK POSTs are never automatically replayed.
- Borrowed/in-flight requests finish before the retired client is shut down.
- The ordinary `500.001.1001` pending STK-query response keeps its connection.
- Staff receive a safe provider-unavailable problem rather than instructions to replace credentials.

The live probe used the actual updated client on Java 25. To isolate the cached-token case, it
seeded that client's cache with a genuinely issued sandbox OAuth token for a five-minute test
window. No production token was fabricated and no token value was written to logs or files.
The following calls all used that same token and the same client instance:

| UTC time, 2026-11-03 | Caller action | Result | Pool replaced | Token unchanged |
|---|---|---|---|---|
| 09:07:11.861 | Initial registration | `500.003.1001` | Yes | Yes |
| 09:07:15.313 | Explicit retry | `00000000` | No | Yes |
| 09:07:18.131 | Healthy pool reuse | `00000000` | No | Yes |
| 09:07:20.865 | Healthy pool reuse | `00000000` | No | Yes |

The probe reported `liveRecoveryConfirmed: true` and `paymentRequests: 0`. No backend restart
or token refresh occurred between these calls. Proof is in ignored
`infra/lab/out/daraja-live-recovery.result.jsonl`.

Contract tests verify new connection use on explicit retry, token retention, healthy pool reuse,
no automatic STK replay, pending-query handling and concurrent in-flight completion. An API
integration test checks safe wording for provider unavailability. `mvnw.cmd -B verify` passed all
464 tests; log: ignored `backend/target/daraja-state-verify.log`.

The local development backend/worker and both browser-stack API instances/worker were updated
without resetting accounts or data. The temporary diagnostic tunnel was closed and the local
callback origin restored afterwards. No frontend change was needed.

## Limit

This recovery removes the need to restart the whole backend after a bad connection. It does
not repair Safaricom's underlying service: a replacement connection may also fail, and the
caller receives that error normally. A sanitized support draft accompanies this record; no
message has been sent to Safaricom. Production readiness still requires the remaining deployment
checks and the pilot's real billing cycles.
