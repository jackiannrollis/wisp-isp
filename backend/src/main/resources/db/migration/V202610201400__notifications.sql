-- Notifications (docs/04 Reminders, docs/03 notifications, M1-M3): per-tenant SMS settings and
-- templates, the outgoing messages with their dedupe keys and provider status, phone numbers the
-- provider refused for good, and alerts for tenant staff.

-- Africa's Talking account of the tenant (the API key is a tenant secret, SMS_API_KEY) and the
-- sending window for reminders (M2). The delivery-report token is kept only as a hash here.
CREATE TABLE notifications_settings (
    tenant_id            uuid        PRIMARY KEY REFERENCES tenancy_tenant (id),
    at_username          text        NOT NULL,
    sender_id            text        NOT NULL,
    sandbox              boolean     NOT NULL DEFAULT false,
    enabled              boolean     NOT NULL DEFAULT true,
    window_start         time        NOT NULL DEFAULT '07:00',
    window_end           time        NOT NULL DEFAULT '21:00',
    callback_token_hash  text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_settings_window_ck CHECK (window_start < window_end),
    CONSTRAINT notifications_settings_sender_ck CHECK (length(sender_id) BETWEEN 1 AND 11)
);

ALTER TABLE notifications_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_settings_tenant_isolation ON notifications_settings
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON notifications_settings TO isp_app;

-- Delivery-report token -> tenant, platform-scoped like payments_callback_route (hashes only).
CREATE TABLE notifications_callback_route (
    token_hash  text        PRIMARY KEY,
    tenant_id   uuid        NOT NULL UNIQUE REFERENCES tenancy_tenant (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE ON notifications_callback_route TO isp_app;


-- A tenant's own wording; without a row the platform default applies.
CREATE TABLE notifications_template (
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    template_key  text        NOT NULL,
    channel       text        NOT NULL DEFAULT 'SMS',
    body          text        NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, template_key, channel),
    CONSTRAINT notifications_template_channel_ck CHECK (channel IN ('SMS')),
    CONSTRAINT notifications_template_body_ck CHECK (length(body) BETWEEN 1 AND 918)
);

ALTER TABLE notifications_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_template_tenant_isolation ON notifications_template
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON notifications_template TO isp_app;


-- M1: (tenant, dedupe_key) is unique, so the same message is never queued twice.
CREATE TABLE notifications_notification (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    customer_id          uuid,
    recipient            text        NOT NULL,
    template_key         text        NOT NULL,
    channel              text        NOT NULL DEFAULT 'SMS',
    body                 text        NOT NULL,
    dedupe_key           text        NOT NULL,
    status               text        NOT NULL,
    scheduled_for        timestamptz NOT NULL,
    next_attempt_at      timestamptz NOT NULL,
    attempts             integer     NOT NULL DEFAULT 0,
    provider_message_id  text,
    provider_status      text,
    failure_reason       text,
    sent_at              timestamptz,
    delivered_at         timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT notifications_notification_dedupe_uk UNIQUE (tenant_id, dedupe_key),
    CONSTRAINT notifications_notification_status_ck CHECK (status IN (
        'QUEUED', 'QUEUED_RETRY', 'SENT', 'DELIVERED', 'FAILED', 'DEAD', 'SUPPRESSED')),
    CONSTRAINT notifications_notification_recipient_ck CHECK (recipient ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE INDEX notifications_notification_due_ix ON notifications_notification (tenant_id, next_attempt_at)
    WHERE status IN ('QUEUED', 'QUEUED_RETRY');
CREATE INDEX notifications_notification_customer_ix ON notifications_notification (tenant_id, customer_id, created_at);
CREATE UNIQUE INDEX notifications_notification_provider_ix ON notifications_notification (tenant_id, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

ALTER TABLE notifications_notification ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_notification_tenant_isolation ON notifications_notification
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON notifications_notification TO isp_app;
GRANT UPDATE (status, scheduled_for, next_attempt_at, attempts, provider_message_id, provider_status, failure_reason,
              sent_at, delivered_at, updated_at) ON notifications_notification TO isp_app;


-- Numbers the provider refused for good (invalid, blacklisted): never retried, shown to staff.
CREATE TABLE notifications_phone_flag (
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    phone       text        NOT NULL,
    status      text        NOT NULL,
    reason      text,
    flagged_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, phone),
    CONSTRAINT notifications_phone_flag_status_ck CHECK (status IN ('INVALID_NUMBER', 'BLACKLISTED'))
);

ALTER TABLE notifications_phone_flag ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_phone_flag_tenant_isolation ON notifications_phone_flag
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON notifications_phone_flag TO isp_app;


-- Problems tenant staff must act on (SMS credit exhausted, sender ID refused, messages dead-lettered).
-- One open alert per kind; cleared when the problem goes away.
CREATE TABLE notifications_alert (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    kind        text        NOT NULL,
    detail      text,
    raised_at   timestamptz NOT NULL,
    cleared_at  timestamptz,
    CONSTRAINT notifications_alert_kind_ck CHECK (kind IN ('SMS_CREDIT_EXHAUSTED', 'SENDER_ID_INVALID', 'SMS_AUTH_FAILED', 'SMS_DEAD_LETTERED'))
);

CREATE UNIQUE INDEX notifications_alert_open_uk ON notifications_alert (tenant_id, kind) WHERE cleared_at IS NULL;

ALTER TABLE notifications_alert ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_alert_tenant_isolation ON notifications_alert
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON notifications_alert TO isp_app;
GRANT UPDATE (detail, cleared_at) ON notifications_alert TO isp_app;
