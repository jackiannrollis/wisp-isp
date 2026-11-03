# Daraja support draft — not sent

Subject: Sandbox C2B 500.003.1001 persists on one retained connection while another succeeds

We reproduced connection-dependent sandbox C2B registration failures on 2026-11-02 using Java 25.
Endpoint: `https://sandbox.safaricom.co.ke/mpesa/c2b/v2/registerurl`.
ShortCode: string `600000`; ResponseType: `Completed`; neutral HTTPS validation/confirmation URLs.

With one unchanged valid OAuth token, identical payload, hostname and HTTP/2 version, alternating
between two retained HTTP clients gave three failures on one client and three successes on the
other. Both HTTP/1.1 and HTTP/2 can encounter the problem; issuing another token does not invalidate
the first, and valid token reuse alone does not explain the failure.

Failure IDs and UTC times:

- 2026-11-02 20:04:18.894 — `135b-4679-8ea6-1c3dca88c6a4839917`
- 2026-11-02 20:04:30.099 — `135b-4679-8ea6-1c3dca88c6a4839929`
- 2026-11-02 20:04:41.058 — `135b-4679-8ea6-1c3dca88c6a4839935`

Each failure was HTTP 500 / `500.003.1001`, with service-unreachable wording. The interleaved
successful calls at 20:04:24.858, 20:04:35.908 and 20:04:46.767 returned HTTP 200 / `00000000`.
DNS resolved the sandbox hostname to `45.223.20.17` during this test.

On 2026-11-03 we confirmed that retiring the failed HTTP pool lets an explicit retry succeed with
the same valid token, without restarting the backend. We do not automatically replay payment
POSTs. No financial transaction was initiated during these diagnostic comparisons.

Please investigate upstream routing/session affinity or backend availability for these request
IDs and advise on a durable resolution. No credentials, token values or private callback paths
are included in this draft.
