-- Display statuses THROTTLED (fair use) and CAPPED, and the reason behind a limited profile
-- (decisions.md 2026-10-21; S1, S3). A line never shows ACTIVE while it is throttled or restricted.
--
-- subscription_access_v3() takes the same inputs as v2 and returns v2's profile and next change
-- unchanged, so the access rules still exist once in SQL; it only derives the display status and the
-- reason on top. v_radius_auth_v6 keeps calling v2 (FreeRADIUS needs neither). AccessPolicy,
-- DisplayStatus and AccessReason are the Java copy; SubscriptionAccessAgreementIT compares them.
--
-- Display status of an ACTIVE line: before expiry CAPPED (data cap reached), else THROTTLED (fair use
-- exceeded), else EXPIRING (the last 72 hours), else ACTIVE; after expiry CAPPED while the cap holds
-- (before the end of grace), RESTRICTED when restricted, otherwise GRACE.
-- Reason: RESTRICTED by PAUSED, DATA_CAP or EXPIRED; THROTTLED by FAIR_USE or GRACE_THROTTLE (the
-- tenant's grace mode); null for FULL and REJECT (the stored state says why).
CREATE FUNCTION subscription_access_v3(
    p_state             text,
    p_expires_at        timestamptz,
    p_grace_days        integer,
    p_paused_at         timestamptz,
    p_pause_until       timestamptz,
    p_grace_mode        text,
    p_fair_use_exceeded boolean,
    p_data_cap_reached  boolean,
    p_at                timestamptz)
RETURNS TABLE (
    effective_state      text,
    effective_expires_at timestamptz,
    profile              text,
    next_change_at       timestamptz,
    display_status       text,
    access_reason        text)
LANGUAGE sql STABLE
AS $$
    SELECT
        a.effective_state,
        a.effective_expires_at,
        a.profile,
        a.next_change_at,
        CASE
            WHEN a.effective_state <> 'ACTIVE' THEN a.effective_state
            WHEN p_at < a.effective_expires_at THEN
                CASE
                    WHEN p_data_cap_reached THEN 'CAPPED'
                    WHEN p_fair_use_exceeded THEN 'THROTTLED'
                    WHEN p_at >= a.effective_expires_at - interval '72 hours' THEN 'EXPIRING'
                    ELSE 'ACTIVE'
                END
            WHEN a.profile = 'RESTRICTED' AND p_data_cap_reached
                 AND p_at < a.effective_expires_at + make_interval(secs => p_grace_days * 86400) THEN 'CAPPED'
            WHEN a.profile = 'RESTRICTED' THEN 'RESTRICTED'
            ELSE 'GRACE'
        END,
        CASE
            WHEN a.profile = 'RESTRICTED' AND a.effective_state = 'PAUSED' THEN 'PAUSED'
            WHEN a.profile = 'RESTRICTED' AND p_data_cap_reached
                 AND p_at < a.effective_expires_at + make_interval(secs => p_grace_days * 86400) THEN 'DATA_CAP'
            WHEN a.profile = 'RESTRICTED' THEN 'EXPIRED'
            WHEN a.profile = 'THROTTLED' AND p_fair_use_exceeded
                 AND p_at < a.effective_expires_at + make_interval(secs => p_grace_days * 86400) THEN 'FAIR_USE'
            WHEN a.profile = 'THROTTLED' THEN 'GRACE_THROTTLE'
        END
    FROM subscription_access_v2(p_state, p_expires_at, p_grace_days, p_paused_at, p_pause_until, p_grace_mode,
                                p_fair_use_exceeded, p_data_cap_reached, p_at) a
$$;

-- The staff functions and view move to v3; their return types grow, so they are recreated. Callers of
-- the previous release keep working: same names, same arguments, the old columns first.
DROP VIEW v_subscription_status;
DROP FUNCTION subscription_line_display_status(uuid, timestamptz);
DROP FUNCTION subscription_line_access(uuid, timestamptz);

CREATE FUNCTION subscription_line_access(p_subscription_id uuid, p_at timestamptz)
RETURNS TABLE (
    effective_state      text,
    effective_expires_at timestamptz,
    profile              text,
    next_change_at       timestamptz,
    display_status       text,
    access_reason        text)
LANGUAGE sql STABLE
AS $$
    SELECT a.effective_state, a.effective_expires_at, a.profile, a.next_change_at, a.display_status, a.access_reason
    FROM subscriptions_subscription s
    JOIN tenancy_tenant t ON t.id = s.tenant_id
    JOIN packages_package p ON p.tenant_id = s.tenant_id AND p.id = s.package_id
    LEFT JOIN network_line_usage u
        ON u.tenant_id = s.tenant_id AND u.subscription_id = s.id AND u.cycle_started_at = s.cycle_started_at
    CROSS JOIN LATERAL subscription_access_v3(
        s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until, t.grace_mode,
        p.fup_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.fup_bytes,
        p.data_cap_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.data_cap_bytes,
        p_at) a
    WHERE s.id = p_subscription_id
$$;

CREATE FUNCTION subscription_line_display_status(p_subscription_id uuid, p_at timestamptz)
RETURNS text
LANGUAGE sql STABLE
AS $$
    SELECT display_status FROM subscription_line_access(p_subscription_id, p_at)
$$;

CREATE VIEW v_subscription_status WITH (security_invoker = true) AS
SELECT
    s.tenant_id,
    s.id          AS subscription_id,
    s.customer_id,
    s.router_id,
    s.package_id,
    s.pppoe_username,
    s.state,
    a.profile     AS access_profile,
    a.display_status,
    a.effective_expires_at,
    a.next_change_at,
    a.access_reason
FROM subscriptions_subscription s
CROSS JOIN LATERAL subscription_line_access(s.id, now()) a;

GRANT SELECT ON v_subscription_status TO isp_app;
