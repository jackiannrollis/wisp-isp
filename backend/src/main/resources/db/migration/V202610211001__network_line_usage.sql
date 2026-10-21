-- Usage of each line in its current cycle, for fair use and data caps (docs/09 step 8b). Fed by the
-- usage roll-up with the same growth it adds to network_usage_daily, so the roll-up stays the only
-- source of usage (N7); this is a running total per line, not a second meter.
--
-- cycle_started_at is the line's cycle as the roll-up last saw it. When the line renews, its
-- subscriptions_subscription.cycle_started_at moves on: readers (the RADIUS view, AccessPolicy's
-- port) count a row whose cycle differs as zero at once, and the next roll-up restarts the total.
-- octets is download plus upload. The *_reached_at columns record that the crossing was announced
-- (UsageThresholdReached, once per line and cycle); access itself never reads them.
CREATE TABLE network_line_usage (
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subscription_id      uuid        NOT NULL,
    cycle_started_at     timestamptz NOT NULL,
    octets               bigint      NOT NULL DEFAULT 0,
    fair_use_reached_at  timestamptz,
    data_cap_reached_at  timestamptz,
    updated_at           timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, subscription_id),
    CONSTRAINT network_line_usage_octets_ck CHECK (octets >= 0)
);

ALTER TABLE network_line_usage ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_line_usage_tenant_isolation ON network_line_usage
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON network_line_usage TO isp_app;
