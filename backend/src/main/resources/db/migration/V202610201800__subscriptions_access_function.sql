-- The access rules (S2, S4, S7) and the display status (S1) as one SQL function, so the RADIUS view
-- (v_radius_auth_v5) and the staff list (v_subscription_status) share a single copy, and
-- subscriptions.domain.AccessPolicy / DisplayStatus are the only other one (S3:
-- SubscriptionAccessAgreementIT compares them for the same inputs and the same instant).
--
-- Inputs are the stored columns, the tenant's grace mode and the instant to judge at. Durations are
-- exact (a day is 86400 seconds, as java.time.Duration counts it), never calendar days, so the result
-- does not depend on the session's time zone.
--
-- A paused line whose pause_until has passed is judged ACTIVE with expires_at + (pause_until -
-- paused_at) (S7, E4). Profile: PENDING, SUSPENDED, TERMINATED -> REJECT; PAUSED -> RESTRICTED;
-- before expiry FULL; within grace FULL or THROTTLED by grace mode; then RESTRICTED. next_change_at
-- is when the profile next changes by the clock (null: never by the clock). Display status adds
-- EXPIRING (the last 72 hours before expiry), GRACE and RESTRICTED for an ACTIVE line.
CREATE FUNCTION subscription_access(
    p_state       text,
    p_expires_at  timestamptz,
    p_grace_days  integer,
    p_paused_at   timestamptz,
    p_pause_until timestamptz,
    p_grace_mode  text,
    p_at          timestamptz)
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

-- The display status alone, for filters in the staff line list.
CREATE FUNCTION subscription_display_status(
    p_state       text,
    p_expires_at  timestamptz,
    p_grace_days  integer,
    p_paused_at   timestamptz,
    p_pause_until timestamptz,
    p_grace_mode  text,
    p_at          timestamptz)
RETURNS text
LANGUAGE sql STABLE
AS $$
    SELECT display_status
    FROM subscription_access(p_state, p_expires_at, p_grace_days, p_paused_at, p_pause_until, p_grace_mode, p_at)
$$;

-- Lines with their access profile and display status now, for staff screens and reports. Unlike the
-- RADIUS views it runs with the caller's rights (security_invoker), so row-level security keeps
-- isp_app to the current tenant (T1).
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
    a.next_change_at
FROM subscriptions_subscription s
JOIN tenancy_tenant t ON t.id = s.tenant_id
CROSS JOIN LATERAL subscription_access(s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until, t.grace_mode, now()) a;

GRANT SELECT ON v_subscription_status TO isp_app;
