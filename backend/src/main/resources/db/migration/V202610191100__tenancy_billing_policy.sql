-- Tenant billing policy (docs/03 tenant, docs/07 per-tenant configuration). The RADIUS view reads
-- grace_mode (S2); default_grace_days and partial_payment_rule are used by renewals (step 4).
-- Defaults are open question Q4 for prepaid tenants: 0 grace days, WAIT_FOR_FULL.

ALTER TABLE tenancy_tenant
    ADD COLUMN default_grace_days   integer NOT NULL DEFAULT 0,
    ADD COLUMN grace_mode           text    NOT NULL DEFAULT 'FULL_SPEED',
    ADD COLUMN partial_payment_rule text    NOT NULL DEFAULT 'WAIT_FOR_FULL',
    ADD CONSTRAINT tenancy_tenant_default_grace_days_ck CHECK (default_grace_days >= 0),
    ADD CONSTRAINT tenancy_tenant_grace_mode_ck CHECK (grace_mode IN ('FULL_SPEED', 'THROTTLED')),
    ADD CONSTRAINT tenancy_tenant_partial_payment_rule_ck
        CHECK (partial_payment_rule IN ('WAIT_FOR_FULL', 'PRO_RATA_DAYS'));
