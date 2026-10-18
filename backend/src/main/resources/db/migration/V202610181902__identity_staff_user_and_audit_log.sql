-- Staff users and the append-only audit log (docs/08, A1, A2).
-- RLS: rows are visible only when tenant_id equals app.tenant_id, which the application sets per
-- transaction. A missing setting yields NULL, so no rows match (fail closed, T1).

CREATE TABLE identity_staff_user (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    email             text        NOT NULL,
    display_name      text        NOT NULL,
    phone             text,
    password_hash     text,
    totp_secret_enc   bytea,
    totp_key_version  integer,
    status            text        NOT NULL DEFAULT 'INVITED',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_staff_user_status_ck CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    CONSTRAINT identity_staff_user_totp_ck CHECK ((totp_secret_enc IS NULL) = (totp_key_version IS NULL))
);

CREATE UNIQUE INDEX identity_staff_user_tenant_email_uk ON identity_staff_user (tenant_id, lower(email));

ALTER TABLE identity_staff_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_staff_user_tenant_isolation ON identity_staff_user
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON identity_staff_user TO isp_app;


-- Append-only (A1): no updated_at, and the application role gets no UPDATE or DELETE.
CREATE TABLE identity_audit_log (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    actor_type      text        NOT NULL,
    actor_id        uuid,
    action          text        NOT NULL,
    target_type     text        NOT NULL,
    target_id       uuid,
    before_state    jsonb,
    after_state     jsonb,
    reason          text,
    correlation_id  text,
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_audit_log_actor_type_ck CHECK (actor_type IN ('STAFF', 'CUSTOMER', 'SYSTEM', 'PLATFORM'))
);

CREATE INDEX identity_audit_log_tenant_target_ix ON identity_audit_log (tenant_id, target_type, target_id, occurred_at);
CREATE INDEX identity_audit_log_tenant_occurred_ix ON identity_audit_log (tenant_id, occurred_at);

ALTER TABLE identity_audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_audit_log_tenant_isolation ON identity_audit_log
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON identity_audit_log TO isp_app;
