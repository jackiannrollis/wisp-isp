-- Payments received (docs/03 payments, docs/05). Step 5 records manual cash, bank and M-Pesa
-- payments (P5); Daraja callbacks, the suspense queue and STK requests arrive in step 6 on the same
-- table. Payments never write the ledger: billing credits it from PaymentReceived (P4).

CREATE TABLE payments_payment (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    channel          text        NOT NULL,
    provider_txn_id  text        NOT NULL,
    account_ref      text,
    msisdn           text,
    payer_name       text,
    amount_minor     bigint      NOT NULL,
    currency         text        NOT NULL DEFAULT 'KES',
    received_at      timestamptz NOT NULL,
    status           text        NOT NULL,
    customer_id      uuid,
    matched_by       uuid,
    matched_at       timestamptz,
    raw_payload      jsonb       NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    -- P1: one payment per provider transaction per tenant; a duplicate is a no-op.
    CONSTRAINT payments_payment_provider_txn_uk UNIQUE (tenant_id, provider_txn_id),
    CONSTRAINT payments_payment_customer_fk FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers_customer (tenant_id, id),
    CONSTRAINT payments_payment_channel_ck CHECK (channel IN ('MPESA_PAYBILL', 'MPESA_STK', 'CASH', 'BANK', 'CARD')),
    CONSTRAINT payments_payment_status_ck CHECK (status IN ('MATCHED', 'UNMATCHED', 'REVERSED')),
    CONSTRAINT payments_payment_amount_ck CHECK (amount_minor > 0),
    CONSTRAINT payments_payment_matched_ck CHECK ((status = 'MATCHED') = (customer_id IS NOT NULL AND matched_at IS NOT NULL))
);

CREATE INDEX payments_payment_tenant_customer_ix ON payments_payment (tenant_id, customer_id, received_at);
CREATE INDEX payments_payment_tenant_status_ix ON payments_payment (tenant_id, status, matched_at);

ALTER TABLE payments_payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY payments_payment_tenant_isolation ON payments_payment
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- P2: the raw payload is never changed once stored.
GRANT SELECT, INSERT ON payments_payment TO isp_app;
GRANT UPDATE (status, customer_id, matched_by, matched_at, updated_at) ON payments_payment TO isp_app;
