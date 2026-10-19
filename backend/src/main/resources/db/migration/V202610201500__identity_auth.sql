-- Identity (docs/08 Security, A1, A2; ADR 0010): roles with permission codes, staff logins with
-- Argon2id passwords and TOTP, Corebalt platform admins, portal credentials (phone + PIN, SMS OTP),
-- rotating refresh tokens in families, impersonation, and the platform audit log.

-- Roles are per tenant; permissions are the fixed codes of shared.security.Permissions (A2).
CREATE TABLE identity_role (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    name          text        NOT NULL,
    permissions   text[]      NOT NULL DEFAULT '{}',
    requires_2fa  boolean     NOT NULL DEFAULT false,
    built_in      boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_role_tenant_id_uk UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX identity_role_tenant_name_uk ON identity_role (tenant_id, lower(name));

ALTER TABLE identity_role ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_role_tenant_isolation ON identity_role
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON identity_role TO isp_app;


-- Staff: role, lockout, token version (bumped on disable, password or role change: revokes at once),
-- invitation expiry and forced change, TOTP enrolment (secret already envelope-encrypted columns).
ALTER TABLE identity_staff_user
    ADD COLUMN role_id               uuid,
    ADD COLUMN token_version         integer     NOT NULL DEFAULT 0,
    ADD COLUMN failed_logins         integer     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          timestamptz,
    ADD COLUMN must_change_password  boolean     NOT NULL DEFAULT true,
    ADD COLUMN password_expires_at   timestamptz,
    ADD COLUMN totp_enabled          boolean     NOT NULL DEFAULT false,
    ADD COLUMN last_login_at         timestamptz,
    ADD CONSTRAINT identity_staff_user_role_fk FOREIGN KEY (tenant_id, role_id) REFERENCES identity_role (tenant_id, id);


-- Corebalt platform admins: not tenant-scoped (no RLS), like the tenant registry. TOTP is mandatory;
-- the secret is encrypted under the platform master key (no tenant data key exists for them).
CREATE TABLE identity_platform_admin (
    id                    uuid        PRIMARY KEY,
    email                 text        NOT NULL,
    display_name          text        NOT NULL,
    password_hash         text        NOT NULL,
    totp_secret_enc       bytea,
    totp_enabled          boolean     NOT NULL DEFAULT false,
    status                text        NOT NULL DEFAULT 'ACTIVE',
    token_version         integer     NOT NULL DEFAULT 0,
    failed_logins         integer     NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    must_change_password  boolean     NOT NULL DEFAULT true,
    last_login_at         timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_platform_admin_status_ck CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX identity_platform_admin_email_uk ON identity_platform_admin (lower(email));

GRANT SELECT, INSERT, UPDATE ON identity_platform_admin TO isp_app;

-- Shown once at TOTP enrolment; stored hashed, each usable once.
CREATE TABLE identity_platform_recovery_code (
    id          uuid        PRIMARY KEY,
    admin_id    uuid        NOT NULL REFERENCES identity_platform_admin (id),
    code_hash   text        NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE ON identity_platform_recovery_code TO isp_app;

-- Append-only, like identity_audit_log, for actions with no tenant (bootstrap, platform logins,
-- break-glass resets). Actions on a tenant (impersonation) also go to that tenant's audit log.
CREATE TABLE identity_platform_audit_log (
    id            uuid        PRIMARY KEY,
    admin_id      uuid,
    action        text        NOT NULL,
    target_type   text,
    target_id     uuid,
    detail        jsonb,
    occurred_at   timestamptz NOT NULL
);

GRANT SELECT, INSERT ON identity_platform_audit_log TO isp_app;


-- Portal logins (docs/02: identity owns portal logins; Q7 default: PIN, SMS OTP to set or reset it).
CREATE TABLE identity_customer_credential (
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    customer_id    uuid        NOT NULL,
    pin_hash       text,
    failed_logins  integer     NOT NULL DEFAULT 0,
    locked_until   timestamptz,
    token_version  integer     NOT NULL DEFAULT 0,
    pin_set_at     timestamptz,
    last_login_at  timestamptz,
    PRIMARY KEY (tenant_id, customer_id),
    CONSTRAINT identity_customer_credential_customer_fk FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers_customer (tenant_id, id)
);

ALTER TABLE identity_customer_credential ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_customer_credential_tenant_isolation ON identity_customer_credential
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON identity_customer_credential TO isp_app;

-- One-time codes: 6 digits, 5 minutes, 3 attempts; stored hashed.
CREATE TABLE identity_otp (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    phone        text        NOT NULL,
    code_hash    text        NOT NULL,
    attempts     integer     NOT NULL DEFAULT 0,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL
);

CREATE INDEX identity_otp_tenant_phone_ix ON identity_otp (tenant_id, phone, created_at);

ALTER TABLE identity_otp ENABLE ROW LEVEL SECURITY;
CREATE POLICY identity_otp_tenant_isolation ON identity_otp
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON identity_otp TO isp_app;


-- Refresh tokens: stored as SHA-256 only, in families. Rotating marks the old token; presenting a
-- rotated token again revokes the whole family (theft detection). Looked up by hash before any
-- tenant is known, so platform-scoped (no RLS); rows hold hashes and IDs, no secrets.
CREATE TABLE identity_refresh_token (
    id            uuid        PRIMARY KEY,
    family_id     uuid        NOT NULL,
    subject_type  text        NOT NULL,
    subject_id    uuid        NOT NULL,
    tenant_id     uuid,
    token_hash    text        NOT NULL,
    expires_at    timestamptz NOT NULL,
    rotated_at    timestamptz,
    revoked_at    timestamptz,
    created_at    timestamptz NOT NULL,
    CONSTRAINT identity_refresh_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT identity_refresh_token_subject_ck CHECK (subject_type IN ('STAFF', 'CUSTOMER', 'PLATFORM'))
);

CREATE INDEX identity_refresh_token_family_ix ON identity_refresh_token (family_id);

GRANT SELECT, INSERT ON identity_refresh_token TO isp_app;
GRANT UPDATE (rotated_at, revoked_at) ON identity_refresh_token TO isp_app;


-- Platform admins acting as a tenant user: at most 30 minutes, audited at start and end in the
-- tenant's own audit log (visible to the tenant) and here.
CREATE TABLE identity_impersonation (
    id               uuid        PRIMARY KEY,
    platform_admin_id uuid       NOT NULL REFERENCES identity_platform_admin (id),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    staff_user_id    uuid        NOT NULL,
    reason           text        NOT NULL,
    started_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    ended_at         timestamptz,
    CONSTRAINT identity_impersonation_window_ck CHECK (expires_at <= started_at + interval '30 minutes')
);

GRANT SELECT, INSERT ON identity_impersonation TO isp_app;
GRANT UPDATE (ended_at) ON identity_impersonation TO isp_app;


-- The real actor when a platform admin acts on behalf of a tenant user (A1).
ALTER TABLE identity_audit_log ADD COLUMN on_behalf_of uuid;
