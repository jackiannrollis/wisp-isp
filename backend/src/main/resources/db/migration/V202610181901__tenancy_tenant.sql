-- Tenant registry (docs/07). Platform-scoped: the tenant row has no tenant_id and no RLS policy,
-- because platform code (TenantIterator, the platform console) must list tenants before any tenant
-- context exists. Every table that belongs to a tenant references it and carries an RLS policy.

CREATE TABLE tenancy_tenant (
    id          uuid        PRIMARY KEY,
    code        text        NOT NULL,
    name        text        NOT NULL,
    subdomain   text        NOT NULL,
    status      text        NOT NULL DEFAULT 'TRIAL',
    brand_color text        NOT NULL DEFAULT '#0f766e',
    logo_url    text,
    time_zone   text        NOT NULL DEFAULT 'Africa/Nairobi',
    currency    text        NOT NULL DEFAULT 'KES',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenancy_tenant_code_uk UNIQUE (code),
    CONSTRAINT tenancy_tenant_subdomain_uk UNIQUE (subdomain),
    -- T2: the code prefixes customer numbers and PPPoE usernames on shared RADIUS servers.
    CONSTRAINT tenancy_tenant_code_ck CHECK (code ~ '^[A-Z]{2,4}$'),
    CONSTRAINT tenancy_tenant_status_ck CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT tenancy_tenant_currency_ck CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT tenancy_tenant_brand_color_ck CHECK (brand_color ~ '^#[0-9a-fA-F]{6}$')
);

GRANT SELECT, INSERT, UPDATE ON tenancy_tenant TO isp_app;
