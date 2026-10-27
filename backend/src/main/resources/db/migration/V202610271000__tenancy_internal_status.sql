-- INTERNAL (ADR 0019): a tenant the platform keeps for itself, today only the probe tenant of the synthetic
-- RADIUS login probe. It is never listed or found by code or subdomain, cannot sign anyone in, and no
-- scheduled job visits it (TenantDirectory visits TRIAL and ACTIVE only).
ALTER TABLE tenancy_tenant DROP CONSTRAINT tenancy_tenant_status_ck;
ALTER TABLE tenancy_tenant ADD CONSTRAINT tenancy_tenant_status_ck
    CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'INTERNAL'));
