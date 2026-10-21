-- v_radius_auth_v7: v_radius_auth_v6 with each line judged by the grace mode it was renewed under
-- (S6 widened, decisions.md 2026-10-21), the tenant's only for lines renewed before
-- V202610211201. A tenant changing its grace policy no longer changes a cycle already paid for.
-- radius/raddb/mods-available/sql switches to it; v6 stays for one release (conventions.md,
-- Migrations). Everything else is unchanged from v6.
CREATE VIEW v_radius_auth_v7 AS
WITH line AS (
    SELECT
        s.tenant_id,
        s.pppoe_username AS username,
        s.static_ip,
        a.effective_state,
        a.profile,
        a.next_change_at,
        k.secret         AS password,
        CASE WHEN p.upload_kbps % 1000 = 0 THEN (p.upload_kbps / 1000) || 'M' ELSE p.upload_kbps || 'k' END
            || '/' ||
        CASE WHEN p.download_kbps % 1000 = 0 THEN (p.download_kbps / 1000) || 'M' ELSE p.download_kbps || 'k' END
            AS rate_limit,
        CASE WHEN p.throttle_upload_kbps % 1000 = 0 THEN (p.throttle_upload_kbps / 1000) || 'M' ELSE p.throttle_upload_kbps || 'k' END
            || '/' ||
        CASE WHEN p.throttle_download_kbps % 1000 = 0 THEN (p.throttle_download_kbps / 1000) || 'M' ELSE p.throttle_download_kbps || 'k' END
            AS throttle_rate_limit
    FROM subscriptions_subscription s
    JOIN customers_customer c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
    JOIN packages_package p ON p.tenant_id = s.tenant_id AND p.id = s.package_id
    JOIN tenancy_tenant t ON t.id = s.tenant_id
    LEFT JOIN network_radius_credential k ON k.tenant_id = s.tenant_id AND k.subscription_id = s.id
    LEFT JOIN network_line_usage u
        ON u.tenant_id = s.tenant_id AND u.subscription_id = s.id AND u.cycle_started_at = s.cycle_started_at
    CROSS JOIN LATERAL subscription_access_v2(
        s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until, coalesce(s.grace_mode, t.grace_mode),
        p.fup_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.fup_bytes,
        p.data_cap_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.data_cap_bytes,
        now()) a
),
attrs AS (
    SELECT
        l.tenant_id,
        l.username,
        host(r.tunnel_ip_a) AS nasname,
        a.ord,
        a.kind,
        a.attribute,
        a.value
    FROM line l
    JOIN network_router r ON r.tenant_id = l.tenant_id
    CROSS JOIN LATERAL (VALUES
        (1, 'check', 'Cleartext-Password',
            CASE WHEN l.profile <> 'REJECT' THEN l.password END),
        (2, 'check', 'Auth-Type',
            CASE WHEN l.profile = 'REJECT' THEN 'Reject' END),
        (3, 'reply', 'Reply-Message',
            CASE l.effective_state
                WHEN 'PENDING' THEN 'Account not yet active'
                WHEN 'SUSPENDED' THEN 'Account suspended'
                WHEN 'TERMINATED' THEN 'Account terminated'
            END),
        (4, 'reply', 'Mikrotik-Rate-Limit',
            CASE l.profile
                WHEN 'FULL' THEN l.rate_limit
                WHEN 'THROTTLED' THEN coalesce(l.throttle_rate_limit, l.rate_limit)
                WHEN 'RESTRICTED' THEN '512k/512k'
            END),
        (5, 'reply', 'Framed-IP-Address',
            CASE WHEN l.profile IN ('FULL', 'THROTTLED') THEN host(l.static_ip) END),
        (6, 'reply', 'Framed-Pool',
            CASE
                WHEN l.profile = 'RESTRICTED' THEN 'restricted'
                WHEN l.profile IN ('FULL', 'THROTTLED') AND l.static_ip IS NULL THEN 'full'
            END),
        (7, 'reply', 'Mikrotik-Address-List',
            CASE l.profile WHEN 'THROTTLED' THEN 'throttled' WHEN 'RESTRICTED' THEN 'restricted' END),
        (8, 'reply', 'Session-Timeout',
            CASE
                WHEN l.profile = 'REJECT' THEN NULL
                WHEN l.next_change_at IS NULL THEN '86400'
                ELSE least(86400, greatest(1, ceil(extract(epoch FROM l.next_change_at - now()))))::bigint::text
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

GRANT SELECT ON v_radius_auth_v7 TO isp_radius;
