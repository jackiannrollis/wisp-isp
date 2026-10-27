# ADR 0009 · RADIUS credentials are stored readable only by FreeRADIUS, not envelope-encrypted

**Status:** Accepted
**Date:** 2026-10-19

## Context

docs/07 required PPPoE passwords and per-router RADIUS shared secrets to be envelope-encrypted, like every other tenant secret: a per-tenant data key wrapped by a platform master key held outside the database. ADR 0004 and rule N2, though, have FreeRADIUS read logins straight from PostgreSQL views, so logins never depend on the API. FreeRADIUS has to compare the password in plain form: PAP needs the cleartext, and CHAP or MS-CHAPv2 need the cleartext or a password-equivalent hash. It cannot unwrap a key that only the application holds. Both requirements cannot hold together, so one had to give way.

## Decision

- PPPoE passwords and router RADIUS shared secrets are stored readable, in one table, `network_radius_credential`. A row is keyed by `subscription_id` for a line's password or by `router_id` for a router's secret. They are not columns on `subscriptions_subscription` or `network_router`.
- Column-level grants keep the application from reading them back:
  - `isp_app` may INSERT rows and UPDATE `secret` and `updated_at`.
  - `isp_app` may SELECT only the key columns, which an UPDATE ... WHERE needs, never `secret`. It has no DELETE.
  - Only the views `radius_client_v3` and `v_radius_auth_v3`, which run as their owner, read `secret`, and `isp_radius` has SELECT on those views alone (N2).
  - A test proves both directions as `isp_app`.
  - Amended 2026-10-20 (step 8, ADR 0011): `isp_worker`, the tunnel worker's role, is the second reader of router secrets, to sign CoA and Disconnect (N5). It has SELECT on `radius_client_v3` (`tenant_id`, `nasname`, `secret`) only, never on `network_radius_credential` or any PPPoE password, and reads the secret when it sends, never caching it, so a rotated secret takes effect on the next request. `DatabaseGrantsIT` pins this exactly. A future Go service for CoA would use the same role.
  - Amended 2026-10-27 (ADR 0019, T4): `isp_worker` also reads the PPPoE passwords of the internal probe tenant's lines, and of no other tenant, through `v_radius_probe_credential_v1`, for the synthetic login probe: the worker logs those lines in through each node every minute, as a router would. The view's tenant id is a literal of its definition, not a parameter or a setting, so the role cannot widen it; `DatabaseGrantsIT` pins the grant and `ProbeIT` proves the role reads exactly the probe lines and no other tenant's line password, through `radius_client_v3` (router secrets only) or any other relation.
- The platform generates every value with `SecureRandom`, from letters and digits without look-alikes:
  - PPPoE passwords have 16 characters.
  - Shared secrets have 32 characters.
- Staff see a new value once, at creation or regeneration. Afterwards the only action is "regenerate", never "reveal". Revoking sets the secret to NULL and keeps the row.
- Only the network module writes the table. The subscriptions module reaches it through the `LineCredentialStore` port, declared in `subscriptions.api` and implemented in `network.infrastructure`. A port is needed because network already depends on subscriptions' events (see context/conventions.md, Backend).
- Every other tenant secret stays envelope-encrypted as docs/07 describes: M-Pesa keys, SMS API keys, router REST and SNMP credentials, and TOTP seeds.

## Consequences

- Logins keep working with the API down (ADR 0004 holds). No key material sits on the login path.
- These credentials are in clear in the database, its streaming replica and its backups. Encrypted backups and full-disk encryption on every database host are therefore stated requirements (docs/08). A database superuser or anyone holding a backup can read them, and that is the residual risk accepted here.
- The API can never leak a stored password, because the application role cannot select one. Staff who lose a password regenerate it.
- Rule T4 has a documented exception for these two kinds of credential (context/domain-rules.md).
- **Hardening path**, if a tenant or auditor requires encryption at rest for these columns: store them with pgcrypto (`pgp_sym_encrypt`) and decrypt inside the views. FreeRADIUS would supply the key per connection through `rlm_sql`'s `open_query`, for example `SET app.radius_key = '…'` from its environment. The key then lives with FreeRADIUS rather than in the database. The cost is a key on every RADIUS node, a decrypt per login, and per-tenant data keys being awkward to express in SQL. The table and grants above stay the same, so the change is confined to the views, the writer and the FreeRADIUS configuration.
