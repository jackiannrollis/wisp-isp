# ADR 0010 · Self-issued RS256 tokens, rotating refresh tokens and per-request revocation

**Status:** Accepted
**Date:** 2026-10-20

## Context

Up to step 7 the API trusted an unsigned stub token in local and test profiles. Step 8 needs real sign-in for four kinds of user: tenant staff, portal customers, Corebalt platform admins, and platform admins acting as a tenant's staff member (support). Disabling a user, a password change or a role change must take effect at once, not when a token expires. No external identity provider is in scope for v1.

## Decision

**Tokens.**
- The platform signs its own JWTs with RS256. Each token carries a `kid`, and every public key, retired ones included, is published at `/.well-known/jwks.json`.
- The private key comes from the environment only (`ISP_JWT_KID`, `ISP_JWT_PRIVATE_KEY`, base64 PKCS#8), never from a file in the image. `infra/scripts/jwt-key` creates or rotates it. A rotation signs new tokens with the new key and keeps the old public key in `ISP_JWT_RETIRED_KEYS`, so nobody is signed out.
- Access tokens last 15 minutes. They carry `typ` (STAFF, CUSTOMER, PLATFORM), `tenant_id`, `role`, permission codes and `ver` (the user's token version).
- The token type decides the API:
  - STAFF tokens call `/api/v1/**`.
  - CUSTOMER tokens call `/api/v1/portal/**`.
  - PLATFORM tokens call `/api/platform/**`.
- Each step of a sign-in returns a 5-minute CHALLENGE token (password change, TOTP, enrolment, PIN setup). No API accepts it.
- The tenant context comes only from the token, never from a header, query parameter or body (T1). The decoder checks the signature, issuer `corebalt-isp`, audience `corebalt-isp-api` and expiry, with no network call.

**Refresh tokens.**
- Refresh tokens are opaque 256-bit values, stored as SHA-256 and grouped in a family per sign-in.
- Every use rotates the token. Presenting a token that was already rotated revokes the whole family (theft detection).
- They travel only in an `isp_rt` cookie: httpOnly, Secure, SameSite=Strict, with `Path` limited to the API's `session` endpoints.
- Those endpoints refuse a request whose `Origin` is not one of `ISP_ALLOWED_ORIGINS`.
- Lifetimes: staff 7 days, customers 90 days, platform admins 12 hours.

**Revocation.**
- Staff, portal credentials and platform admins carry a `token_version`. Disabling, a password or PIN change, a new temporary password, a role change, or an edit of the role's permissions bumps it.
- The bump also revokes the user's refresh tokens where the change ends every session.
- Each request compares the token's `ver` with the current version, read from Redis (10-minute cache) or, on a miss or a Redis failure, from PostgreSQL. It never fails open.
- The cache entry is deleted on the bump and again after the commit.

**Staff.**
- Passwords are Argon2id hashes, at least 12 characters.
- Five failures lock the account for 15 minutes, and every refusal says the same thing.
- An invitation is a generated 14-character temporary password, shown once and valid 72 hours. An unused invitation can only be regenerated, never shown again.
- The first sign-in forces a new password.
- TOTP (RFC 6238, ±1 step) is required when the role holds any of `staff.manage`, `settings.secrets`, `billing.adjust`, `payments.record_manual`, `payments.assign` or `payments.reconcile` (A2: finance and admin roles). The role's flag is computed from its codes, never set by hand.
- Built-in roles: Owner (all codes, not editable), Finance, Support, Technician.
- A tenant always keeps one active staff member who can manage staff, and nobody can disable or re-role themselves.

**Portal (Q7 default: PIN, with an SMS code to set or reset it).**
- A sign-in code has 6 digits, is valid 5 minutes and allows 3 attempts, then a new code is needed. Codes are stored hashed.
- Send limits:
  - Per phone: 3 in 10 minutes and 10 a day, counted in PostgreSQL.
  - Per client address: 10 in 10 minutes and 50 a day, in Redis. These are higher than the per-phone limits because many customers share one carrier-grade NAT address.
- More than 30 codes in 5 minutes for one tenant, or an address hitting its limit, logs an error and increments `isp.identity.otp.bursts`.
- The answer to a code request never reveals whether the phone is a customer's.
- The SMS is sent at once, outside the queue. The message log stores it with the code masked (M3).
- PINs are 4 to 6 digits, not all the same and not a run, hashed with Argon2id, with the same 5-failure, 15-minute lockout.

**Platform admins.**
- TOTP is mandatory: no token is issued before enrolment. Ten single-use recovery codes are shown once and stored hashed.
- The first admin is created at startup from `ISP_BOOTSTRAP_ADMIN_EMAIL` and `ISP_BOOTSTRAP_ADMIN_PASSWORD` only while the platform-admin table is empty. The startup log tells the operator to remove the variables, and a non-empty table ignores them.
- Break-glass is `java -jar app.jar reset-platform-admin <email>` inside the container (`infra/scripts/reset-platform-admin`), never an HTTP endpoint. It prints a temporary password to the terminal, clears TOTP and recovery codes, and ends every session.
- Bootstrap and break-glass write the platform audit log.

**Impersonation.**
- An impersonation token is a separate STAFF token for the chosen active staff member. It carries `act` (the platform admin) and `imp` (the impersonation id), lasts at most 30 minutes (also a database check) and has no refresh token.
- Start and end, by the admin or on expiry, are written to the tenant's own audit log as actor PLATFORM with `on_behalf_of` the staff member, and to the platform audit log.
- Ending it makes the token fail at the next request.
- Amended by [ADR 0017](0017-impersonation-hand-off.md): the token reaches the admin app only by exchanging a single-use code, and never carries the permissions support may not use.

## Consequences

- No identity provider to run or pay for, and tokens verify locally. The platform owns key rotation, which `jwt-key --rotate` scripts.
- Every authenticated request costs one Redis GET, or one indexed PostgreSQL read when Redis is down. That is the price of immediate revocation.
- The unsigned stub decoder is gone from every profile. Tests sign real tokens with a key generated per test JVM.
- The lab fixture (`infra/lab/seed.sql`) adds a lab admin with an obvious fake password and a role with every code and no 2FA, so scripts can sign in unattended. This is the only such role and it never leaves a developer machine.
- Not built: the staff option "send the temporary password by SMS" conflicts with M3 ("messages never include secrets") and waits for a decision (context/open-questions.md, Q11).
