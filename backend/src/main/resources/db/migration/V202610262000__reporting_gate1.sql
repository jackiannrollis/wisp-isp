-- Gate 1 measured by the platform (docs/09, ADR 0018): how long a paying customer waited for the
-- platform to restore them. Two facts, recorded by the reporting module from events, and the time a
-- restore completed, filled by its job:
--
--   reporting_restore          a payment that arrived by M-Pesa callback renewed a RESTRICTED line
--                              (CreditApplied, the line restored()): when the platform first recorded the
--                              callback and when the renewal happened. restored_at is the renewal itself
--                              when the line had no live session (its next login is FULL, NO_SESSION);
--                              otherwise the first of a Disconnect or CoA the router took or found already
--                              gone (REAUTHORIZED, SESSION_GONE) or a new session of the line (NEW_SESSION),
--                              within 10 minutes of the renewal. Still null after that, the restore is
--                              counted as missed.
--   reporting_reauthorization  a CoA or Disconnect answered by the router (SessionReauthorized), kept
--                              because its event may arrive before the credit's.
CREATE TABLE reporting_restore (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    payment_id       uuid        NOT NULL,
    subscription_id  uuid        NOT NULL,
    callback_at      timestamptz NOT NULL,
    renewed_at       timestamptz NOT NULL,
    restored_at      timestamptz,
    restored_by      text,
    PRIMARY KEY (tenant_id, payment_id, subscription_id),
    CONSTRAINT reporting_restore_by_ck CHECK (restored_by IN ('NO_SESSION', 'REAUTHORIZED', 'SESSION_GONE', 'NEW_SESSION')),
    CONSTRAINT reporting_restore_done_ck CHECK ((restored_at IS NULL) = (restored_by IS NULL))
);

CREATE INDEX reporting_restore_tenant_renewed_ix ON reporting_restore (tenant_id, renewed_at);
CREATE INDEX reporting_restore_open_ix ON reporting_restore (tenant_id, renewed_at) WHERE restored_at IS NULL;

ALTER TABLE reporting_restore ENABLE ROW LEVEL SECURITY;
CREATE POLICY reporting_restore_tenant_isolation ON reporting_restore
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON reporting_restore TO isp_app;
GRANT UPDATE (restored_at, restored_by) ON reporting_restore TO isp_app;

CREATE TABLE reporting_reauthorization (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    event_id         uuid        NOT NULL,
    subscription_id  uuid        NOT NULL,
    occurred_at      timestamptz NOT NULL,
    session_gone     boolean     NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);

CREATE INDEX reporting_reauthorization_line_ix ON reporting_reauthorization (tenant_id, subscription_id, occurred_at);

ALTER TABLE reporting_reauthorization ENABLE ROW LEVEL SECURITY;
CREATE POLICY reporting_reauthorization_tenant_isolation ON reporting_reauthorization
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON reporting_reauthorization TO isp_app;
