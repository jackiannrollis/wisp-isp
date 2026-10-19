-- Tenant secrets, envelope-encrypted (docs/07 Secrets, T4): every tenant has a data key, stored
-- only wrapped (AES-256-GCM) by the platform master key, which lives outside the database
-- (ISP_MASTER_KEY in the environment). Secrets are encrypted with the tenant's data key. Key
-- rotation adds a data-key version and re-wraps; ciphertext names the version it was made with.
-- PPPoE passwords and router RADIUS secrets are the exception (ADR 0009) and are not stored here.

CREATE TABLE tenancy_tenant_data_key (
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    version       integer     NOT NULL,
    wrapped_key   bytea       NOT NULL,
    wrap_nonce    bytea       NOT NULL,
    master_key_id text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version),
    CONSTRAINT tenancy_tenant_data_key_version_ck CHECK (version >= 1)
);

ALTER TABLE tenancy_tenant_data_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenancy_tenant_data_key_tenant_isolation ON tenancy_tenant_data_key
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON tenancy_tenant_data_key TO isp_app;


CREATE TABLE tenancy_tenant_secret (
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    kind         text        NOT NULL,
    ciphertext   bytea       NOT NULL,
    nonce        bytea       NOT NULL,
    key_version  integer     NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, kind),
    CONSTRAINT tenancy_tenant_secret_key_fk FOREIGN KEY (tenant_id, key_version)
        REFERENCES tenancy_tenant_data_key (tenant_id, version),
    CONSTRAINT tenancy_tenant_secret_kind_ck CHECK (kind IN (
        'MPESA_CONSUMER_KEY', 'MPESA_CONSUMER_SECRET', 'MPESA_PASSKEY',
        'MPESA_INITIATOR_NAME', 'MPESA_SECURITY_CREDENTIAL', 'SMS_API_KEY'))
);

ALTER TABLE tenancy_tenant_secret ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenancy_tenant_secret_tenant_isolation ON tenancy_tenant_secret
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Replaced, never deleted by the application (docs/07: replaced rather than edited).
GRANT SELECT, INSERT ON tenancy_tenant_secret TO isp_app;
GRANT UPDATE (ciphertext, nonce, key_version, updated_at) ON tenancy_tenant_secret TO isp_app;
