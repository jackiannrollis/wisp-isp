-- Subscription lifecycle (step 4, docs/04): pause clock, activation and termination times, and the
-- reminders already raised (M1 at the source).

ALTER TABLE subscriptions_subscription
    ADD COLUMN paused_at     timestamptz,
    ADD COLUMN pause_until   timestamptz,
    ADD COLUMN activated_at  timestamptz,
    ADD COLUMN terminated_at timestamptz;

-- S7: a PAUSED line always has its pause window, and only a PAUSED line has one. NOT VALID: the
-- only rows older than this migration are developer lab fixtures; every new or updated row is
-- checked. infra/lab/seed.sql sets the window for its paused line.
ALTER TABLE subscriptions_subscription
    ADD CONSTRAINT subscriptions_subscription_pause_ck CHECK (
        (state = 'PAUSED') = (paused_at IS NOT NULL)
        AND (paused_at IS NULL) = (pause_until IS NULL)
        AND (pause_until IS NULL OR pause_until > paused_at)) NOT VALID,
    ADD CONSTRAINT subscriptions_subscription_terminated_ck CHECK (
        (state = 'TERMINATED') = (terminated_at IS NOT NULL)) NOT VALID;

CREATE INDEX subscriptions_subscription_tenant_state_expires_ix
    ON subscriptions_subscription (tenant_id, state, expires_at);


-- One row per reminder raised, keyed by the M1 dedupe key <EVENT>:<subscription id>:<expires_at>,
-- so an hourly rerun never raises the same reminder twice. Insert-only for the application.
CREATE TABLE subscriptions_reminder (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subscription_id  uuid        NOT NULL,
    event_key        text        NOT NULL,
    expires_at       timestamptz NOT NULL,
    dedupe_key       text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_reminder_subscription_fk FOREIGN KEY (tenant_id, subscription_id)
        REFERENCES subscriptions_subscription (tenant_id, id),
    CONSTRAINT subscriptions_reminder_dedupe_uk UNIQUE (tenant_id, dedupe_key),
    CONSTRAINT subscriptions_reminder_event_key_ck CHECK (
        event_key IN ('EXPIRING_3D', 'EXPIRING_1D', 'DUE_TODAY', 'GRACE_STARTED', 'RESTRICTED'))
);

ALTER TABLE subscriptions_reminder ENABLE ROW LEVEL SECURITY;
CREATE POLICY subscriptions_reminder_tenant_isolation ON subscriptions_reminder
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON subscriptions_reminder TO isp_app;
