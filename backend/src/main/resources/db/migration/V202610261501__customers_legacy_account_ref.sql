-- The account reference a customer used in the ISP's old system (ADR 0014, P3): payers keep
-- typing it for months after a cutover, so M-Pesa matching tries it, exactly, right after the
-- customer number and before the normalised reference (a digits-only old reference would otherwise
-- be read as a short customer number). Stored trimmed and upper-cased; unique per tenant; cleared
-- by staff once the old numbers are forgotten.
ALTER TABLE customers_customer
    ADD COLUMN legacy_account_ref text,
    ADD CONSTRAINT customers_customer_legacy_account_ref_ck
        CHECK (legacy_account_ref ~ '^[A-Z0-9][A-Z0-9 ._/#-]{0,31}$');

CREATE UNIQUE INDEX customers_customer_tenant_legacy_account_ref_uk
    ON customers_customer (tenant_id, legacy_account_ref)
    WHERE legacy_account_ref IS NOT NULL;
