# Daraja callback fixtures

Shapes of production Daraja callbacks (docs/05), with `${...}` placeholders the tests fill in:

| File | What it stands for |
|---|---|
| `c2b-v2-confirmation-masked.json` | C2B v2 confirmation; MSISDN masked (`2547 ***** 126`) |
| `c2b-v1-confirmation-hashed.json` | C2B v1 confirmation from a shortcode that sends SHA-256(MSISDN) |
| `c2b-validation.json` | C2B validation request (only if external validation is enabled) |
| `stk-callback-success.json` | STK Push result, ResultCode 0, with CallbackMetadata |
| `stk-callback-failed.json` | STK Push result without metadata: 1032 cancelled, 1037 unreachable, 2001 wrong PIN |
| `txn-status-result.json` | Transaction Status result for a completed payment |

All numbers, names and IDs are fakes.
