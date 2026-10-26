-- Gate 1 as accepted (ADR 0018, 2026-10-27):
--
--   reporting_restore.kind      what the payment lifted: CUT_OFF (RESTRICTED: expired past grace or a data
--                               cap), GRACE_THROTTLE or FAIR_USE (THROTTLED back to full speed). Every row
--                               written before this migration was a RESTRICTED line, so CUT_OFF.
--   reporting_manual_reconnect  hands that were needed: STAFF_DISCONNECT, a staff member's "Disconnect
--                               sessions" on a line within 2 hours after the customer's latest payment
--                               (LineDisconnectedByStaff); PAID_OFFLINE, support's "Paid but still offline"
--                               on a line, linked to the customer's latest payment. Actor and reason kept.
ALTER TABLE reporting_restore
    ADD COLUMN kind text NOT NULL DEFAULT 'CUT_OFF'
        CONSTRAINT reporting_restore_kind_ck CHECK (kind IN ('CUT_OFF', 'GRACE_THROTTLE', 'FAIR_USE'));

CREATE TABLE reporting_manual_reconnect (
    tenant_id                 uuid        NOT NULL REFERENCES tenancy_tenant (id),
    id                        uuid        NOT NULL,
    kind                      text        NOT NULL,
    subscription_id           uuid        NOT NULL,
    payment_id                uuid        NOT NULL,
    payment_matched_at        timestamptz NOT NULL,
    occurred_at               timestamptz NOT NULL,
    staff_id                  uuid,
    acting_platform_admin_id  uuid,
    reason                    text,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT reporting_manual_reconnect_kind_ck CHECK (kind IN ('STAFF_DISCONNECT', 'PAID_OFFLINE'))
);

CREATE INDEX reporting_manual_reconnect_tenant_occurred_ix ON reporting_manual_reconnect (tenant_id, occurred_at);

ALTER TABLE reporting_manual_reconnect ENABLE ROW LEVEL SECURITY;
CREATE POLICY reporting_manual_reconnect_tenant_isolation ON reporting_manual_reconnect
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON reporting_manual_reconnect TO isp_app;
