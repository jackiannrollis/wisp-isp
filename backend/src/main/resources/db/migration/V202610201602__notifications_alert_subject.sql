-- Staff alerts per router: an alert can now name its subject, and stays one open alert per kind and
-- subject (a tenant with two routers down has two alerts).
ALTER TABLE notifications_alert ADD COLUMN subject_id uuid;
ALTER TABLE notifications_alert DROP CONSTRAINT notifications_alert_kind_ck;
ALTER TABLE notifications_alert ADD CONSTRAINT notifications_alert_kind_ck
    CHECK (kind IN ('SMS_CREDIT_EXHAUSTED', 'SENDER_ID_INVALID', 'SMS_AUTH_FAILED', 'SMS_DEAD_LETTERED', 'ROUTER_OFFLINE'));
DROP INDEX notifications_alert_open_uk;
CREATE UNIQUE INDEX notifications_alert_open_uk
    ON notifications_alert (tenant_id, kind, coalesce(subject_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE cleared_at IS NULL;
