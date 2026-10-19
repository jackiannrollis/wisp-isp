-- M-Pesa (docs/05): tenant settings, the callback route, the raw callback inbox and STK requests.

-- One Paybill or Till per tenant. Credentials are tenant secrets (tenancy_tenant_secret, T4); the
-- callback token is stored only as a hash. validation_enabled: Q10 default is "assume not".
CREATE TABLE payments_mpesa_config (
    tenant_id            uuid        PRIMARY KEY REFERENCES tenancy_tenant (id),
    shortcode            text        NOT NULL,
    shortcode_type       text        NOT NULL,
    sandbox              boolean     NOT NULL,
    callback_token_hash  text        NOT NULL,
    validation_enabled   boolean     NOT NULL DEFAULT false,
    urls_registered_at   timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payments_mpesa_config_type_ck CHECK (shortcode_type IN ('PAYBILL', 'TILL')),
    CONSTRAINT payments_mpesa_config_shortcode_ck CHECK (shortcode ~ '^[0-9]{5,7}$')
);

ALTER TABLE payments_mpesa_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY payments_mpesa_config_tenant_isolation ON payments_mpesa_config
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON payments_mpesa_config TO isp_app;


-- Callback path token -> tenant (docs/05 Callback handling 1, docs/07). Platform-scoped like the
-- tenant registry: a callback carries no tenant until its token is resolved, so this table has no
-- RLS. It holds SHA-256 hashes only; the token itself exists only in the URLs given to Safaricom.
CREATE TABLE payments_callback_route (
    token_hash  text        PRIMARY KEY,
    tenant_id   uuid        NOT NULL UNIQUE REFERENCES tenancy_tenant (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE ON payments_callback_route TO isp_app;


-- P2: every callback body is stored, as received (even malformed), before anything else; processing
-- runs after the response and is retried from this copy until processed_at is set.
CREATE TABLE payments_mpesa_callback (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    kind          text        NOT NULL,
    raw_body      text        NOT NULL,
    source_ip     text,
    received_at   timestamptz NOT NULL,
    processed_at  timestamptz,
    attempts      integer     NOT NULL DEFAULT 0,
    last_error    text,
    payment_id    uuid,
    CONSTRAINT payments_mpesa_callback_kind_ck CHECK (kind IN (
        'C2B_VALIDATION', 'C2B_CONFIRMATION', 'STK_RESULT', 'TXN_STATUS_RESULT', 'TXN_STATUS_TIMEOUT'))
);

CREATE INDEX payments_mpesa_callback_pending_ix ON payments_mpesa_callback (tenant_id, received_at) WHERE processed_at IS NULL;
CREATE INDEX payments_mpesa_callback_received_ix ON payments_mpesa_callback (tenant_id, kind, received_at);

ALTER TABLE payments_mpesa_callback ENABLE ROW LEVEL SECURITY;
CREATE POLICY payments_mpesa_callback_tenant_isolation ON payments_mpesa_callback
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON payments_mpesa_callback TO isp_app;
GRANT UPDATE (processed_at, attempts, last_error, payment_id) ON payments_mpesa_callback TO isp_app;


-- STK Push requests (docs/05 STK Push, P6). Only PENDING ever changes, under a row lock.
CREATE TABLE payments_stk_request (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    customer_id          uuid        NOT NULL,
    amount_minor         bigint      NOT NULL,
    currency             text        NOT NULL DEFAULT 'KES',
    phone                text        NOT NULL,
    account_ref          text        NOT NULL,
    checkout_request_id  text,
    merchant_request_id  text,
    status               text        NOT NULL,
    result_code          text,
    result_desc          text,
    payment_id           uuid,
    queried_at           timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT payments_stk_request_customer_fk FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers_customer (tenant_id, id),
    CONSTRAINT payments_stk_request_checkout_uk UNIQUE (tenant_id, checkout_request_id),
    CONSTRAINT payments_stk_request_status_ck CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'TIMED_OUT')),
    CONSTRAINT payments_stk_request_amount_ck CHECK (amount_minor > 0 AND amount_minor % 100 = 0)
);

CREATE INDEX payments_stk_request_pending_ix ON payments_stk_request (tenant_id, created_at) WHERE status = 'PENDING';

ALTER TABLE payments_stk_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY payments_stk_request_tenant_isolation ON payments_stk_request
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON payments_stk_request TO isp_app;
GRANT UPDATE (checkout_request_id, merchant_request_id, status, result_code, result_desc, payment_id, queried_at, updated_at)
    ON payments_stk_request TO isp_app;

-- The suspense queue reads unmatched payments newest first.
CREATE INDEX payments_payment_tenant_unmatched_ix ON payments_payment (tenant_id, received_at) WHERE status = 'UNMATCHED';
