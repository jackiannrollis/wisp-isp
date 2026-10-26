# Drill record: <letter and name, e.g. A, RADIUS node outage>

Copy to `drills/<yyyy-mm-dd>-<inventory>-<letter>.md` and commit it with the next change. It holds no secrets and no customer's number: the drill line's number is the only one that may appear (`../failover-drill.md`, The record).

| | |
|---|---|
| Date and window (UTC) | |
| Inventory and host | |
| Operator, witness | |
| Release deployed (`stackctl status`) | |
| Tenants informed (production) | |

## Baseline

- Alerts firing before the drill (besides the Watchdog):
- Sessions before: on the router (`/ppp active print count-only`), in the platform (Sessions screen):
- Login check before (the drill line): accepted at hh:mm:ss

## Timeline

| Time (UTC) | Event | Seen where |
|---|---|---|
| T0 | | |
| | first alert pending | Prometheus, Alerts |
| | first alert firing, notification received | Alertmanager, email or webhook |
| T1 | service back | |
| | first login accepted after T1 | `corebalt-radtest` or the auth log in Loki |
| | alert resolved, notification received | |

## Result

- Sessions after:
- Expected effects seen (`../failover-drill.md` lists them per drill):
- Unexpected:
- Passed or failed, against the criteria of `../failover-drill.md`:

## Follow-ups

- 
