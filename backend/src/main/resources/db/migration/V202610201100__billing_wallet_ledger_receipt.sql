-- Wallet and append-only ledger (docs/05, ADR 0005, B1-B4, B7).

-- One wallet per customer. balance_minor caches the latest balance_after_minor (B4); every ledger
-- write row-locks the wallet first (B3), so concurrent credits and charges for one customer
-- serialise.
CREATE TABLE billing_wallet (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenancy_tenant (id),
    customer_id    uuid        NOT NULL,
    currency       text        NOT NULL DEFAULT 'KES',
    balance_minor  bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT billing_wallet_customer_fk FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customers_customer (tenant_id, id),
    CONSTRAINT billing_wallet_customer_uk UNIQUE (tenant_id, customer_id),
    CONSTRAINT billing_wallet_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT billing_wallet_currency_ck CHECK (currency ~ '^[A-Z]{3}$')
);

ALTER TABLE billing_wallet ENABLE ROW LEVEL SECURITY;
CREATE POLICY billing_wallet_tenant_isolation ON billing_wallet
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON billing_wallet TO isp_app;
GRANT UPDATE (balance_minor, updated_at) ON billing_wallet TO isp_app;


-- B1: append-only. No updated_at; the application role has INSERT and SELECT only.
-- B3: credits positive, charges negative; balance_after_minor written in the same transaction.
-- (reference_type, reference_id) is unique: one cause, one entry, so a redelivered event can never
-- credit or charge twice even if a consumer's processed-event row were missing.
CREATE TABLE billing_ledger_entry (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    wallet_id            uuid        NOT NULL,
    type                 text        NOT NULL,
    amount_minor         bigint      NOT NULL,
    balance_after_minor  bigint      NOT NULL,
    currency             text        NOT NULL,
    reference_type       text        NOT NULL,
    reference_id         uuid        NOT NULL,
    description          text        NOT NULL,
    reason               text,
    created_by           uuid,
    created_at           timestamptz NOT NULL,
    CONSTRAINT billing_ledger_entry_wallet_fk FOREIGN KEY (tenant_id, wallet_id)
        REFERENCES billing_wallet (tenant_id, id),
    CONSTRAINT billing_ledger_entry_reference_uk UNIQUE (reference_type, reference_id),
    CONSTRAINT billing_ledger_entry_type_ck CHECK (type IN (
        'PAYMENT', 'RENEWAL_CHARGE', 'PRORATION_CHARGE', 'POSTPAID_CHARGE', 'ADJUSTMENT',
        'CREDIT_NOTE', 'REFUND', 'REVERSAL', 'COMMISSION')),
    CONSTRAINT billing_ledger_entry_sign_ck CHECK (
        amount_minor <> 0
        AND (type NOT IN ('PAYMENT', 'CREDIT_NOTE') OR amount_minor > 0)
        AND (type NOT IN ('RENEWAL_CHARGE', 'PRORATION_CHARGE', 'POSTPAID_CHARGE', 'REFUND') OR amount_minor < 0)),
    -- B1: corrections carry a reason.
    CONSTRAINT billing_ledger_entry_reason_ck CHECK (type NOT IN ('ADJUSTMENT', 'REVERSAL') OR reason IS NOT NULL)
);

CREATE INDEX billing_ledger_entry_wallet_ix ON billing_ledger_entry (tenant_id, wallet_id, created_at, id);

ALTER TABLE billing_ledger_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY billing_ledger_entry_tenant_isolation ON billing_ledger_entry
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON billing_ledger_entry TO isp_app;


-- B7: one receipt per credited payment, numbered per tenant, never reused or reissued.
-- The PDF (pdf_key) is rendered later, for the portal.
CREATE TABLE billing_receipt (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    number        text        NOT NULL,
    payment_id    uuid        NOT NULL,
    customer_id   uuid        NOT NULL,
    ledger_entry_id uuid      NOT NULL REFERENCES billing_ledger_entry (id),
    amount_minor  bigint      NOT NULL,
    currency      text        NOT NULL,
    issued_at     timestamptz NOT NULL,
    pdf_key       text,
    CONSTRAINT billing_receipt_number_uk UNIQUE (tenant_id, number),
    CONSTRAINT billing_receipt_payment_uk UNIQUE (tenant_id, payment_id),
    CONSTRAINT billing_receipt_amount_ck CHECK (amount_minor > 0)
);

ALTER TABLE billing_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY billing_receipt_tenant_isolation ON billing_receipt
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON billing_receipt TO isp_app;
GRANT UPDATE (pdf_key) ON billing_receipt TO isp_app;

CREATE TABLE billing_receipt_counter (
    tenant_id   uuid    PRIMARY KEY REFERENCES tenancy_tenant (id),
    last_value  bigint  NOT NULL,
    CONSTRAINT billing_receipt_counter_ck CHECK (last_value >= 1)
);

ALTER TABLE billing_receipt_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY billing_receipt_counter_tenant_isolation ON billing_receipt_counter
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON billing_receipt_counter TO isp_app;
