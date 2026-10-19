-- Package catalogue (docs/03 packages). Speeds in kbit/s; the RADIUS view turns them into
-- Mikrotik-Rate-Limit as upload/download (N4). Price in minor units (B2).

CREATE TABLE packages_package (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenancy_tenant (id),
    name                    text        NOT NULL,
    type                    text        NOT NULL DEFAULT 'PPPOE',
    download_kbps           integer     NOT NULL,
    upload_kbps             integer     NOT NULL,
    duration_minutes        integer     NOT NULL,
    price_minor             bigint      NOT NULL,
    currency                text        NOT NULL DEFAULT 'KES',
    grace_days              integer,
    data_cap_bytes          bigint,
    fup_bytes               bigint,
    throttle_download_kbps  integer,
    throttle_upload_kbps    integer,
    device_limit            integer,
    billing_mode            text        NOT NULL DEFAULT 'PREPAID',
    active                  boolean     NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT packages_package_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT packages_package_type_ck CHECK (type IN ('PPPOE', 'HOTSPOT')),
    CONSTRAINT packages_package_speed_ck CHECK (download_kbps > 0 AND upload_kbps > 0),
    CONSTRAINT packages_package_duration_ck CHECK (duration_minutes > 0),
    CONSTRAINT packages_package_price_ck CHECK (price_minor >= 0),
    CONSTRAINT packages_package_currency_ck CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT packages_package_grace_days_ck CHECK (grace_days IS NULL OR grace_days >= 0),
    -- Throttle speeds come as a pair; null means no throttle (docs/03: null means unlimited).
    CONSTRAINT packages_package_throttle_ck CHECK (
        (throttle_download_kbps IS NULL) = (throttle_upload_kbps IS NULL)
        AND (throttle_download_kbps IS NULL OR (throttle_download_kbps > 0 AND throttle_upload_kbps > 0))),
    CONSTRAINT packages_package_caps_ck CHECK (
        (data_cap_bytes IS NULL OR data_cap_bytes > 0) AND (fup_bytes IS NULL OR fup_bytes > 0)),
    CONSTRAINT packages_package_device_limit_ck CHECK (device_limit IS NULL OR device_limit > 0),
    CONSTRAINT packages_package_billing_mode_ck CHECK (billing_mode IN ('PREPAID', 'POSTPAID'))
);

CREATE UNIQUE INDEX packages_package_tenant_name_uk ON packages_package (tenant_id, lower(name));

ALTER TABLE packages_package ENABLE ROW LEVEL SECURITY;
CREATE POLICY packages_package_tenant_isolation ON packages_package
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON packages_package TO isp_app;
