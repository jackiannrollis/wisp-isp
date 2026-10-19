-- FreeRADIUS views over the real tables (step 3 of docs/09), replacing the step-2 lab views.
-- Same profile logic as v_radius_auth_v2 (S2, S4, N3, N4); what changed is where the inputs come
-- from: subscription state and dates, package speeds, the tenant's grace mode and the credential
-- table (ADR 0009). radius/raddb/mods-available/sql switches to these names.
--
-- Both views run with their owner's rights (not security_invoker), so isp_radius needs SELECT on
-- the views only (N2) and row-level security does not hide rows from FreeRADIUS, which has no
-- tenant context. Tenant isolation is the router-tenant join (T3).

-- rlm_sql client list: routers with a current secret, by their tunnel address on hub A.
CREATE VIEW radius_client_v3 AS
SELECT
    row_number() OVER (ORDER BY r.created_at, r.id) AS id,
    r.tenant_id,
    host(r.tunnel_ip_a)                             AS nasname,
    r.name                                          AS shortname,
    'other'::text                                   AS type,
    k.secret                                        AS secret,
    NULL::text                                      AS server,
    'router ' || r.id::text                         AS description
FROM network_router r
JOIN network_radius_credential k ON k.router_id = r.id AND k.tenant_id = r.tenant_id
WHERE k.secret IS NOT NULL;

-- rlm_sql check and reply attributes per username and asking router.
--
-- Profile (S2), evaluated in this order at query time:
--   PENDING, SUSPENDED, TERMINATED          -> REJECT
--   PAUSED                                  -> RESTRICTED
--   now < expires_at                        -> FULL
--   now < expires_at + grace_days           -> FULL, or THROTTLED when the tenant's grace_mode is THROTTLED
--   otherwise                               -> RESTRICTED
-- Rates (N4): Mikrotik-Rate-Limit is upload/download in RouterOS units, kbit/s from the package
-- written as M when divisible by 1000 (5000/10000 -> 5M/10M), else k. A package without throttle
-- speeds stays at full speed when THROTTLED (docs/03: null means unlimited).
-- Session-Timeout (S4): seconds to the next profile change, capped at 86400. Before expiry the next
-- change is expires_at when grace is throttled or zero, otherwise the end of grace (docs/04).
-- A line without a current password gets no Cleartext-Password, so PAP fails (revoked credential).
CREATE VIEW v_radius_auth_v3 AS
WITH line AS (
    SELECT
        s.tenant_id,
        s.pppoe_username AS username,
        s.state,
        s.expires_at,
        s.grace_days,
        s.static_ip,
        t.grace_mode,
        k.secret         AS password,
        CASE WHEN p.upload_kbps % 1000 = 0 THEN (p.upload_kbps / 1000) || 'M' ELSE p.upload_kbps || 'k' END
            || '/' ||
        CASE WHEN p.download_kbps % 1000 = 0 THEN (p.download_kbps / 1000) || 'M' ELSE p.download_kbps || 'k' END
            AS rate_limit,
        CASE WHEN p.throttle_upload_kbps % 1000 = 0 THEN (p.throttle_upload_kbps / 1000) || 'M' ELSE p.throttle_upload_kbps || 'k' END
            || '/' ||
        CASE WHEN p.throttle_download_kbps % 1000 = 0 THEN (p.throttle_download_kbps / 1000) || 'M' ELSE p.throttle_download_kbps || 'k' END
            AS throttle_rate_limit,
        s.expires_at + make_interval(days => s.grace_days) AS grace_ends_at,
        CASE
            WHEN s.state IN ('PENDING', 'SUSPENDED', 'TERMINATED') THEN 'REJECT'
            WHEN s.state = 'PAUSED' THEN 'RESTRICTED'
            WHEN now() < s.expires_at THEN 'FULL'
            WHEN now() < s.expires_at + make_interval(days => s.grace_days)
                THEN CASE t.grace_mode WHEN 'THROTTLED' THEN 'THROTTLED' ELSE 'FULL' END
            ELSE 'RESTRICTED'
        END AS profile
    FROM subscriptions_subscription s
    JOIN customers_customer c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
    JOIN packages_package p ON p.tenant_id = s.tenant_id AND p.id = s.package_id
    JOIN tenancy_tenant t ON t.id = s.tenant_id
    LEFT JOIN network_radius_credential k ON k.tenant_id = s.tenant_id AND k.subscription_id = s.id
),
timed AS (
    SELECT
        line.*,
        CASE
            WHEN line.profile = 'FULL' AND now() < line.expires_at
                 AND (line.grace_mode = 'THROTTLED' OR line.grace_days = 0) THEN line.expires_at
            WHEN line.profile IN ('FULL', 'THROTTLED') THEN line.grace_ends_at
        END AS next_change_at
    FROM line
),
attrs AS (
    SELECT
        t.tenant_id,
        t.username,
        host(r.tunnel_ip_a) AS nasname,
        a.ord,
        a.kind,
        a.attribute,
        a.value
    FROM timed t
    JOIN network_router r ON r.tenant_id = t.tenant_id
    CROSS JOIN LATERAL (VALUES
        (1, 'check', 'Cleartext-Password',
            CASE WHEN t.profile <> 'REJECT' THEN t.password END),
        (2, 'check', 'Auth-Type',
            CASE WHEN t.profile = 'REJECT' THEN 'Reject' END),
        (3, 'reply', 'Reply-Message',
            CASE t.state
                WHEN 'PENDING' THEN 'Account not yet active'
                WHEN 'SUSPENDED' THEN 'Account suspended'
                WHEN 'TERMINATED' THEN 'Account terminated'
            END),
        (4, 'reply', 'Mikrotik-Rate-Limit',
            CASE t.profile
                WHEN 'FULL' THEN t.rate_limit
                WHEN 'THROTTLED' THEN coalesce(t.throttle_rate_limit, t.rate_limit)
                WHEN 'RESTRICTED' THEN '512k/512k'
            END),
        (5, 'reply', 'Framed-IP-Address',
            CASE WHEN t.profile IN ('FULL', 'THROTTLED') THEN host(t.static_ip) END),
        (6, 'reply', 'Framed-Pool',
            CASE
                WHEN t.profile = 'RESTRICTED' THEN 'restricted'
                WHEN t.profile IN ('FULL', 'THROTTLED') AND t.static_ip IS NULL THEN 'full'
            END),
        (7, 'reply', 'Mikrotik-Address-List',
            CASE t.profile WHEN 'THROTTLED' THEN 'throttled' WHEN 'RESTRICTED' THEN 'restricted' END),
        (8, 'reply', 'Session-Timeout',
            CASE
                WHEN t.profile = 'REJECT' THEN NULL
                WHEN t.next_change_at IS NULL THEN '86400'
                ELSE least(86400, greatest(1, ceil(extract(epoch FROM t.next_change_at - now()))))::bigint::text
            END)
    ) AS a (ord, kind, attribute, value)
    WHERE a.value IS NOT NULL
)
SELECT
    ord::bigint AS id,
    tenant_id,
    username,
    nasname,
    kind,
    attribute,
    ':='::text  AS op,
    value
FROM attrs;

GRANT SELECT ON radius_client_v3, v_radius_auth_v3 TO isp_radius;
