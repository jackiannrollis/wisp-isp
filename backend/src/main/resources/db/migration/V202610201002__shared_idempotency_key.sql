-- Idempotency keys for API writes that move money or change access (conventions.md, API): the first
-- request with a key stores its response in the same transaction as the business change; a replay
-- with the same key and the same request gets that response back; the same key with a different
-- request is refused. A request that fails rolls back its key, so it can be retried.
-- No DELETE for the application; an owner-role maintenance job will purge old keys.

CREATE TABLE shared_idempotency_key (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    idempotency_key  text        NOT NULL,
    fingerprint      text        NOT NULL,
    response_status  integer,
    response_body    jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT shared_idempotency_key_key_ck CHECK (length(idempotency_key) BETWEEN 1 AND 100)
);

ALTER TABLE shared_idempotency_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY shared_idempotency_key_tenant_isolation ON shared_idempotency_key
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON shared_idempotency_key TO isp_app;
GRANT UPDATE (response_status, response_body) ON shared_idempotency_key TO isp_app;
