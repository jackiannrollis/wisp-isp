# CHR lab validation — 2026-11-02

The previously unrun Hyper-V portion of step 2 was exercised on two MikroTik CHR 7.24.5
Generation 1 VMs, with 512 MiB RAM each. The image ZIP was checked against MikroTik's published
SHA-256: `4a5efa354a29505e66dc1aff1206ef251d0b27577074c33e9a7545a7f1bb0495`.
The download and checksum are at
[MikroTik's versioned download](https://download.mikrotik.com/routeros/7.24.5/chr-7.24.5.vhdx.zip.sha256).

The router and customer CHR share the private `corebalt-lab-lan` switch. Their management
interfaces use Hyper-V's Default Switch. The router peers over WireGuard with the separate
`corebalt-isp` development Compose project, whose API uses Java 25. The pre-existing `corebalt`
stack and monitoring project were left running. Fixtures were loaded only into the development
database. No production host, provider credential or physical ISP router was used.

## Observed checks

| Check | Result |
|---|---|
| Fresh CHR bootstrap | REST accepted the initial blank admin password; the lab set its configured password on both VMs. |
| WireGuard | The router reached hub `10.200.0.1`, three replies out of three. No host firewall change was required. |
| Onboarding template | RouterOS accepted the WireGuard peer name and RADIUS `timeout=1500ms`. PPPoE authenticated through FreeRADIUS and PostgreSQL. |
| FULL — CB000001 | Address `10.10.0.250`, queue `5000000/10000000`; HTTP, HTTPS, DNS and three public ICMP replies succeeded. |
| CoA — N5 | A real `CoA-ACK`; queue changed to `2000000/4000000` with session uptime continuing (5m15s when observed). |
| Expiry and Disconnect — N3, N5 | A real `Disconnect-ACK`; the client redialled at `10.11.0.250`, queue `512000/512000`, dynamic `restricted` address-list entry. |
| Walled garden | HTTP to example.com returned the lab pay-page marker; DNS resolved; HTTPS failed; public ping received zero replies. |
| Payment-driven restore | A fake KES 2,500 cash payment through `POST /api/v1/payments/manual` restored FULL automatically in 4.287 seconds observed from the payment request. Session changed from `0x81000001` to `0x81000002`, address returned to `10.10.0.250`, queue to `5000000/10000000`. No manual Disconnect or expiry edit was sent during this restore. |
| Payment replay | The same idempotency key returned the original payment, with one ledger credit and one receipt. |
| Final repeat | Automatic restore in 4.150 seconds, session `0x81000021` to `0x81000022`, FULL address `10.10.0.248`, queue `5000000/10000000`. Exactly one payment credit, one `RENEWAL_CHARGE`, one receipt, zero remaining wallet balance, and an idempotent replay. HTTP, HTTPS, DNS (client cache flushed) and ping passed afterwards. |
| Grace throttle — CB000002 | Address `10.10.0.249`, queue `1000000/2000000`, dynamic `throttled` address-list entry. |
| Static IP — CB000003 | Address `10.10.0.53`, queue `5000000/10000000`. |
| Session-Timeout — S4 | With CB000001's expiry one minute ahead and zero grace, the router redialled from session `0x81000005` to `0x81000006` in 58.374 seconds after the initial connection was observed; the new session was restricted at `10.11.0.250`, `512000/512000`. No Disconnect request was sent. |
| Suspended and pending — N3 | CB000006 and CB000007 each remained disconnected during a 30-second attempt, with no PPP session or queue. |
| Tenant isolation — T3 | Active ZZ000001 was refused through CB's CHR, with no PPP session or queue during the 30-second attempt. |

## Repairs made while running the lab

- REST array results are explicitly enumerated before filtering rows. Previously the DHCP client
  lookup failed even though RouterOS reported a bound client and gateway.
- Fetch uses `as-value` and a bounded idle timeout. Ping prints an explicit result marker. The
  previous checks compared progress output with an exact result string and misclassified working
  full access as a failure. DNS is checked after flushing the client cache.
- Known management addresses can be supplied after VM creation. Hyper-V discovery still requires
  Windows elevation; ordinary RouterOS checks need no elevated VM-discovery step.
- Failed RADIUS requests and expiry updates stop the script. Login checks can explicitly expect
  rejection instead of returning success for either outcome.
- `payment-restore.ps1` reproduces the actual payment-to-CHR road, including idempotency and ledger
  checks. `lab.ps1 session-timeout` reproduces the bounded expiry test. Both write sanitized results
  to the ignored `infra/lab/out/` folder.

## Limits and remaining validation

This proves RouterOS behaviour in CHR, not throughput on physical ISP hardware. The
[free CHR licence](https://help.mikrotik.com/docs/spaces/ROS/pages/18350234/Cloud+Hosted+Router+CHR)
limits throughput; speeds above were checked in the router's queue configuration.
The pay page was the local network-lab stub. Production TLS and portal reachability from an ISP's
restricted customer network still need checking during onboarding.

The payment was fake cash in the lab. Its timing starts at the local manual-payment request, not
a Daraja callback, and is **not a Gate 1 observation** (ADR 0018). Celcom Africa SMS was subsequently
verified live; Daraja C2B sandbox credit/receipt/renewal and a successful KES 1 STK sandbox callback/credit/receipt also passed (see
`providers-validation-2026-11-02.md`). Physical router onboarding, a real staging host, node B and failover, and
the pilot's two billing cycles remain outstanding.

The VMs and separate development stack remain available for inspection. The lab contains fake
payments and modified fixture dates; seed again only when intentionally resetting its scenarios.

The later [combined Daraja-to-CHR check](daraja-restore-validation-2026-11-02.md) used a genuine
sandbox callback to restore the same virtual router in 4.357 seconds, with platform reporting at
3.328 seconds and one credit/renewal/receipt. That adds callback-based lab evidence; production
Gate 1 still requires the pilot's two billing cycles. The record also tracks a provider-state
registration issue subsequently diagnosed as connection-dependent upstream failure; the
[confirmed recovery](daraja-state-validation-2026-11-03.md) replaces a failed pool without
automatically replaying payment requests.
