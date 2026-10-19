-- Longest pause a customer may take (docs/04 Pause, S7): a tenant setting, default 30 days.

ALTER TABLE tenancy_tenant
    ADD COLUMN max_pause_days integer NOT NULL DEFAULT 30,
    ADD CONSTRAINT tenancy_tenant_max_pause_days_ck CHECK (max_pause_days BETWEEN 1 AND 365);
