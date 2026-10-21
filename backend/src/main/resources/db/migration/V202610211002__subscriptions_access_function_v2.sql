-- Fair use and data caps in the access rules (docs/09 step 8b; S2, S3). subscription_access() stays as
-- it is for v_radius_auth_v5, which keeps working for one release (conventions.md, Migrations);
-- v_radius_auth_v6 and the staff screens use v2. AccessPolicy carries the same two inputs, and
-- SubscriptionAccessAgreementIT compares them (S3).
--
-- The rules, in order (S2 as amended): PENDING, SUSPENDED, TERMINATED -> REJECT; PAUSED -> RESTRICTED;
-- then, while the dates still give access (before the end of grace): data cap reached -> RESTRICTED
-- (Q14 default), fair use exceeded -> THROTTLED; otherwise the date rules of v1. The throttle and the
-- cap end with the cycle, at the next renewal, which is an event and not a clock boundary, so
-- next_change_at keeps following the dates (a throttled line next changes at the end of grace, a
-- capped line not by the clock). The display status is unchanged in kind: it follows the dates, and
-- after expiry shows RESTRICTED whenever the profile is RESTRICTED.
CREATE FUNCTION subscription_access_v2(
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
    display_status       text)
LANGUAGE sql STABLE
AS $$
    WITH effective AS (
        SELECT
            CASE WHEN p_state = 'PAUSED' AND p_at >= p_pause_until THEN 'ACTIVE' ELSE p_state END AS st,
            CASE WHEN p_state = 'PAUSED' AND p_at >= p_pause_until
                 THEN p_expires_at + make_interval(secs => extract(epoch FROM p_pause_until - p_paused_at))
                 ELSE p_expires_at END AS exp
    ),
    judged AS (
        SELECT
            st,
            exp,
            exp + make_interval(secs => p_grace_days * 86400) AS grace_end,
            CASE
                WHEN st IN ('PENDING', 'SUSPENDED', 'TERMINATED') THEN 'REJECT'
                WHEN st = 'PAUSED' THEN 'RESTRICTED'
                WHEN p_at < exp + make_interval(secs => p_grace_days * 86400) AND p_data_cap_reached THEN 'RESTRICTED'
                WHEN p_at < exp + make_interval(secs => p_grace_days * 86400) AND p_fair_use_exceeded THEN 'THROTTLED'
                WHEN p_at < exp THEN 'FULL'
                WHEN p_at < exp + make_interval(secs => p_grace_days * 86400)
                    THEN CASE p_grace_mode WHEN 'THROTTLED' THEN 'THROTTLED' ELSE 'FULL' END
                ELSE 'RESTRICTED'
            END AS profile
        FROM effective
    )
    SELECT
        st,
        exp,
        profile,
        CASE
            WHEN st = 'PAUSED' THEN p_pause_until
            WHEN profile = 'FULL' AND p_at < exp AND (p_grace_mode = 'THROTTLED' OR p_grace_days = 0) THEN exp
            WHEN profile IN ('FULL', 'THROTTLED') THEN grace_end
        END,
        CASE
            WHEN st <> 'ACTIVE' THEN st
            WHEN p_at < exp - interval '72 hours' THEN 'ACTIVE'
            WHEN p_at < exp THEN 'EXPIRING'
            WHEN profile = 'RESTRICTED' THEN 'RESTRICTED'
            ELSE 'GRACE'
        END
    FROM judged
$$;

-- One line, judged with its own usage: the current package's fup_bytes and data_cap_bytes against the
-- octets of the line's current cycle (network_line_usage, counted as zero when it holds another cycle
-- or the cycle start is unknown). For the staff screens, which run as isp_app under row-level
-- security; the RADIUS view computes the same two inputs in its own joins, because isp_radius may
-- only read the views (a function body runs with the caller's rights).
CREATE FUNCTION subscription_line_access(p_subscription_id uuid, p_at timestamptz)
RETURNS TABLE (
    effective_state      text,
    effective_expires_at timestamptz,
    profile              text,
    next_change_at       timestamptz,
    display_status       text)
LANGUAGE sql STABLE
AS $$
    SELECT a.effective_state, a.effective_expires_at, a.profile, a.next_change_at, a.display_status
    FROM subscriptions_subscription s
    JOIN tenancy_tenant t ON t.id = s.tenant_id
    JOIN packages_package p ON p.tenant_id = s.tenant_id AND p.id = s.package_id
    LEFT JOIN network_line_usage u
        ON u.tenant_id = s.tenant_id AND u.subscription_id = s.id AND u.cycle_started_at = s.cycle_started_at
    CROSS JOIN LATERAL subscription_access_v2(
        s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until, t.grace_mode,
        p.fup_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.fup_bytes,
        p.data_cap_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.data_cap_bytes,
        p_at) a
    WHERE s.id = p_subscription_id
$$;

-- The display status alone, for the staff line list's filter.
CREATE FUNCTION subscription_line_display_status(p_subscription_id uuid, p_at timestamptz)
RETURNS text
LANGUAGE sql STABLE
AS $$
    SELECT display_status FROM subscription_line_access(p_subscription_id, p_at)
$$;

-- The staff view now judges each line with its usage. Same columns as before.
CREATE OR REPLACE VIEW v_subscription_status WITH (security_invoker = true) AS
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
    a.next_change_at
FROM subscriptions_subscription s
CROSS JOIN LATERAL subscription_line_access(s.id, now()) a;
