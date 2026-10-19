-- Credentials FreeRADIUS must read in clear (ADR 0009, T4 exception): PPPoE passwords keyed by
-- subscription and per-router RADIUS shared secrets keyed by router. Everything else secret stays
-- envelope-encrypted (docs/07).
--
-- Column-level grants: isp_app may INSERT rows and UPDATE the secret, and may SELECT only the key
-- columns (an UPDATE ... WHERE needs them), never the secret. Only the RADIUS views, which run as
-- their owner, read it, and isp_radius has SELECT on those views alone (N2). The platform generates
-- every value; staff see a new value once and can only regenerate it, never reveal it.
-- revoke sets the secret to NULL: the row stays so the key is not reissued by accident.

CREATE TABLE network_radius_credential (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subscription_id  uuid,
    router_id        uuid,
    secret           text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT network_radius_credential_subject_ck
        CHECK ((subscription_id IS NULL) <> (router_id IS NULL)),
    CONSTRAINT network_radius_credential_subscription_uk UNIQUE (subscription_id),
    CONSTRAINT network_radius_credential_router_uk UNIQUE (router_id),
    CONSTRAINT network_radius_credential_subscription_fk FOREIGN KEY (tenant_id, subscription_id)
        REFERENCES subscriptions_subscription (tenant_id, id),
    CONSTRAINT network_radius_credential_router_fk FOREIGN KEY (tenant_id, router_id)
        REFERENCES network_router (tenant_id, id),
    CONSTRAINT network_radius_credential_secret_ck CHECK (secret IS NULL OR length(secret) >= 12)
);

ALTER TABLE network_radius_credential ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_radius_credential_tenant_isolation ON network_radius_credential
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT (id, tenant_id, subscription_id, router_id, created_at, updated_at)
    ON network_radius_credential TO isp_app;
GRANT INSERT ON network_radius_credential TO isp_app;
GRANT UPDATE (secret, updated_at) ON network_radius_credential TO isp_app;
