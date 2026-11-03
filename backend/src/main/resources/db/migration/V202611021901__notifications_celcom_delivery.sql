-- ADR 0020: only messages explicitly sent by Celcom are polled with Celcom credentials.
-- Old rows remain legacy; a settings change never reinterprets their provider IDs.
ALTER TABLE notifications_notification ADD COLUMN sms_provider text NOT NULL DEFAULT 'AFRICAS_TALKING';
ALTER TABLE notifications_notification ADD COLUMN delivery_check_at timestamptz;
ALTER TABLE notifications_notification ADD CONSTRAINT notifications_notification_provider_ck
    CHECK (sms_provider IN ('AFRICAS_TALKING', 'CELCOM_AFRICA'));
CREATE INDEX notifications_notification_delivery_idx
    ON notifications_notification (tenant_id, delivery_check_at, sent_at)
    WHERE status = 'SENT' AND sms_provider = 'CELCOM_AFRICA';
GRANT UPDATE (sms_provider, delivery_check_at) ON notifications_notification TO isp_app;
