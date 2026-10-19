-- v_radius_auth_v4 (step 4): v3 plus the pause clock (S7, E4). radius_client_v3 is unchanged and
-- stays in use; v_radius_auth_v3 stays in place for one release (conventions.md, Migrations).
--
-- A paused line resumes at pause_until without waiting for any job (E4): from pause_until on, the
-- view evaluates it as ACTIVE with expires_at + (pause_until - paused_at), which is exactly what the
-- job later records. Before pause_until it is RESTRICTED with Session-Timeout = seconds to
-- pause_until (S4), so the session ends when the pause does.
--
-- Profile (S2, S7), evaluated in this order on the effective state and expiry:
--   PENDING, SUSPENDED, TERMINATED          -> REJECT
--   PAUSED (now < pause_until)              -> RESTRICTED
--   now < expires_at                        -> FULL
--   now < expires_at + grace_days           -> FULL, or THROTTLED when the tenant's grace_mode is THROTTLED
--   otherwise                               -> RESTRICTED
-- subscriptions.domain.AccessPolicy implements the same rules; SubscriptionAccessAgreementIT
-- proves they agree, profile and Session-Timeout, for the same inputs and the same now() (S3).
CREATE VIEW v_radius_auth_v4 AS
WITH base AS (
    SELECT
        s.*,
        (s.state = 'PAUSED' AND now() >= s.pause_until) AS pause_over
    FROM subscriptions_subscription s
),
line AS (
    SELECT
        b.tenant_id,
        b.pppoe_username AS username,
        b.state,
        CASE WHEN b.pause_over THEN 'ACTIVE' ELSE b.state END AS effective_state,
        CASE WHEN b.pause_over THEN b.expires_at + (b.pause_until - b.paused_at) ELSE b.expires_at END
            AS effective_expires_at,
        b.pause_until,
        b.grace_days,
        b.static_ip,
        t.grace_mode,
        k.secret         AS password,
        CASE WHEN p.upload_kbps % 1000 = 0 THEN (p.upload_kbps / 1000) || 'M' ELSE p.upload_kbps || 'k' END
            || '/' ||
        CASE WHEN p.download_kbps % 1000 = 0 THEN (p.download_kbps / 1000) || 'M' ELSE p.download_kbps || 'k' END
            AS rate_limit,
        CASE WHEN p.throttle_upload_kbps % 1000 = 0 THEN (p.throttle_upload_kbps / 1000) || 'M' ELSE p.throttle_upload_kbps || 'k' END
            || '/' ||
        CASE WHEN p.throttle_download_kbps % 1000 = 0 THEN (p.throttle_download_kbps / 1000) || 'M' ELSE p.throttle_download_kbps || 'k' END
            AS throttle_rate_limit
    FROM base b
    JOIN customers_customer c ON c.tenant_id = b.tenant_id AND c.id = b.customer_id
    JOIN packages_package p ON p.tenant_id = b.tenant_id AND p.id = b.package_id
    JOIN tenancy_tenant t ON t.id = b.tenant_id
    LEFT JOIN network_radius_credential k ON k.tenant_id = b.tenant_id AND k.subscription_id = b.id
),
profiled AS (
    SELECT
        line.*,
        line.effective_expires_at + make_interval(days => line.grace_days) AS grace_ends_at,
        CASE
            WHEN line.effective_state IN ('PENDING', 'SUSPENDED', 'TERMINATED') THEN 'REJECT'
            WHEN line.effective_state = 'PAUSED' THEN 'RESTRICTED'
            WHEN now() < line.effective_expires_at THEN 'FULL'
            WHEN now() < line.effective_expires_at + make_interval(days => line.grace_days)
                THEN CASE line.grace_mode WHEN 'THROTTLED' THEN 'THROTTLED' ELSE 'FULL' END
            ELSE 'RESTRICTED'
        END AS profile
    FROM line
),
timed AS (
    SELECT
        p.*,
        CASE
            WHEN p.effective_state = 'PAUSED' THEN p.pause_until
            WHEN p.profile = 'FULL' AND now() < p.effective_expires_at
                 AND (p.grace_mode = 'THROTTLED' OR p.grace_days = 0) THEN p.effective_expires_at
            WHEN p.profile IN ('FULL', 'THROTTLED') THEN p.grace_ends_at
        END AS next_change_at
    FROM profiled p
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
            CASE t.effective_state
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

GRANT SELECT ON v_radius_auth_v4 TO isp_radius;
