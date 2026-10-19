-- Reconciliation runs (docs/05 Reconciliation): an uploaded M-Pesa statement compared with the
-- payments the platform recorded, both directions, plus matched payments that never reached the
-- ledger (the P4 guard). The report is kept with the run; runs are never changed.

CREATE TABLE payments_reconciliation_run (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    source            text        NOT NULL,
    period_from       timestamptz NOT NULL,
    period_to         timestamptz NOT NULL,
    statement_rows    integer     NOT NULL,
    missing_count     integer     NOT NULL,
    extra_count       integer     NOT NULL,
    uncredited_count  integer     NOT NULL,
    report            jsonb       NOT NULL,
    created_by        uuid,
    created_at        timestamptz NOT NULL,
    CONSTRAINT payments_reconciliation_run_source_ck CHECK (source IN ('STATEMENT_CSV', 'TRANSACTION_STATUS')),
    CONSTRAINT payments_reconciliation_run_period_ck CHECK (period_to >= period_from)
);

CREATE INDEX payments_reconciliation_run_tenant_ix ON payments_reconciliation_run (tenant_id, created_at);

ALTER TABLE payments_reconciliation_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY payments_reconciliation_run_tenant_isolation ON payments_reconciliation_run
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON payments_reconciliation_run TO isp_app;
