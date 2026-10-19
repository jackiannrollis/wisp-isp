-- Customer profiles (docs/03 customers): profile, contact and location only. Lines, PPPoE usernames
-- and credentials belong to subscriptions and network (ADR 0009).
-- National ID (encrypted) and the portal PIN arrive with the secret store and the portal.

CREATE TABLE customers_customer (
    id                     uuid          PRIMARY KEY,
    tenant_id              uuid          NOT NULL REFERENCES tenancy_tenant (id),
    number                 text          NOT NULL,
    name                   text          NOT NULL,
    phone                  text          NOT NULL,
    email                  text,
    address                text,
    installation_location  text,
    lat                    numeric(9, 6),
    lng                    numeric(9, 6),
    status                 text          NOT NULL DEFAULT 'ACTIVE',
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    -- T2: the number is the tenant code plus a six-digit sequence, unique across the platform
    -- because it is also the M-Pesa account reference and the first PPPoE username.
    CONSTRAINT customers_customer_number_uk UNIQUE (number),
    CONSTRAINT customers_customer_number_ck CHECK (number ~ '^[A-Z]{2,4}[0-9]{6}$'),
    -- Lets other modules' tables reference a customer of the same tenant only.
    CONSTRAINT customers_customer_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT customers_customer_phone_ck CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT customers_customer_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT customers_customer_lat_ck CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT customers_customer_lng_ck CHECK (lng BETWEEN -180 AND 180)
);

CREATE INDEX customers_customer_tenant_phone_ix ON customers_customer (tenant_id, phone);
CREATE INDEX customers_customer_tenant_name_ix ON customers_customer (tenant_id, lower(name));

ALTER TABLE customers_customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY customers_customer_tenant_isolation ON customers_customer
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON customers_customer TO isp_app;


-- Per-tenant customer number sequence (T2). Allocation is a single upsert that row-locks the
-- tenant's counter; the unique number is the backstop.
CREATE TABLE customers_number_counter (
    tenant_id   uuid    PRIMARY KEY REFERENCES tenancy_tenant (id),
    last_value  integer NOT NULL,
    CONSTRAINT customers_number_counter_range_ck CHECK (last_value BETWEEN 1 AND 999999)
);

ALTER TABLE customers_number_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY customers_number_counter_tenant_isolation ON customers_number_counter
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON customers_number_counter TO isp_app;
