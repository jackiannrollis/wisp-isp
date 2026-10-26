-- The cutover import (ADR 0014, S11, T2 widened; decisions.md 2026-10-26).
--
-- 1. Kept PPPoE usernames. An ISP's existing customers have routers configured with the old
--    system's username and password, so the import may keep them (username_kept). A kept name is
--    not in the generated shape (CB001238, CB001238-2: the import refuses one that is, so generated
--    names stay unique across the platform) and is unique within its tenant only: FreeRADIUS asks
--    the view for the username together with the asking router's address, the view lists a line
--    once per router of its own tenant (T3), and the accounting trigger resolves the tenant from
--    the router before it looks the username up. The platform-wide unique constraint goes; a plain
--    index on the username stays for the view's lookups by username alone.
ALTER TABLE subscriptions_subscription
    DROP CONSTRAINT subscriptions_subscription_pppoe_username_uk,
    DROP CONSTRAINT subscriptions_subscription_pppoe_username_ck,
    ADD CONSTRAINT subscriptions_subscription_tenant_pppoe_username_uk UNIQUE (tenant_id, pppoe_username),
    -- rlm_sql's safe characters, without space, colon and slash; 64 is RouterOS's limit.
    ADD CONSTRAINT subscriptions_subscription_pppoe_username_ck CHECK (pppoe_username ~ '^[A-Za-z0-9@._-]{1,64}$'),
    ADD COLUMN username_kept boolean NOT NULL DEFAULT false;

CREATE INDEX subscriptions_subscription_pppoe_username_ix ON subscriptions_subscription (pppoe_username);

-- 2. Import batches: what one CSV import did, with the totals of its preview, and which customers
--    and lines it created, so the "we've moved" SMS can go to exactly those customers and the audit
--    row of the batch has something to point at. Append-only.
CREATE TABLE subscriptions_import_batch (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    router_id            uuid,
    imported_by          uuid,
    rows_total           integer     NOT NULL,
    lines_total          integer     NOT NULL,
    credentials_kept     integer     NOT NULL,
    legacy_refs          integer     NOT NULL,
    activated            integer     NOT NULL,
    activated_expired    integer     NOT NULL,
    activated_future     integer     NOT NULL,
    earliest_paid_until  timestamptz,
    latest_paid_until    timestamptz,
    value_granted_minor  bigint      NOT NULL DEFAULT 0,
    currency             text        NOT NULL DEFAULT 'KES',
    warnings             jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at           timestamptz NOT NULL,
    CONSTRAINT subscriptions_import_batch_counts_ck
        CHECK (rows_total >= 0 AND lines_total >= 0 AND activated >= 0 AND activated_expired + activated_future = activated)
);

ALTER TABLE subscriptions_import_batch ENABLE ROW LEVEL SECURITY;
CREATE POLICY subscriptions_import_batch_tenant_isolation ON subscriptions_import_batch
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX subscriptions_import_batch_tenant_created_ix ON subscriptions_import_batch (tenant_id, created_at DESC);

GRANT SELECT, INSERT ON subscriptions_import_batch TO isp_app;

CREATE TABLE subscriptions_import_batch_row (
    batch_id         uuid    NOT NULL REFERENCES subscriptions_import_batch (id),
    tenant_id        uuid    NOT NULL REFERENCES tenancy_tenant (id),
    row_number       integer NOT NULL,
    customer_id      uuid    NOT NULL,
    subscription_id  uuid,
    PRIMARY KEY (batch_id, row_number)
);

ALTER TABLE subscriptions_import_batch_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY subscriptions_import_batch_row_tenant_isolation ON subscriptions_import_batch_row
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON subscriptions_import_batch_row TO isp_app;
