-- The staff function judges each line by the grace mode it was renewed under (V202610211201), the
-- tenant's only for lines renewed before that column existed. Same signature and columns, so the
-- staff view and filter that call it need no change.
CREATE OR REPLACE FUNCTION subscription_line_access(p_subscription_id uuid, p_at timestamptz)
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
        s.state, s.expires_at, s.grace_days, s.paused_at, s.pause_until, coalesce(s.grace_mode, t.grace_mode),
        p.fup_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.fup_bytes,
        p.data_cap_bytes IS NOT NULL AND coalesce(u.octets, 0) >= p.data_cap_bytes,
        p_at) a
    WHERE s.id = p_subscription_id
$$;
