-- Transactional outbox (docs/02, E1, ADR 0003) and consumer idempotency (E2).
--
-- outbox_event is transport plumbing, not a record of truth (conventions.md, Database): the
-- application role may INSERT and UPDATE only published_at, attempts and last_error; no DELETE.
-- Published rows older than 7 days are purged by an owner-role maintenance job.

CREATE TABLE outbox_event (
    id              uuid        PRIMARY KEY,           -- the event's eventId (UUIDv7, time-ordered)
    tenant_id       uuid        NOT NULL REFERENCES tenancy_tenant (id),
    module          text        NOT NULL,
    event_type      text        NOT NULL,
    version         integer     NOT NULL,
    routing_key     text        NOT NULL,              -- <module>.<Event>.v<version>
    payload         jsonb       NOT NULL,
    correlation_id  text,
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      text,
    CONSTRAINT outbox_event_version_ck CHECK (version > 0)
);

CREATE INDEX outbox_event_unpublished_ix ON outbox_event (tenant_id, id) WHERE published_at IS NULL;
CREATE INDEX outbox_event_published_ix ON outbox_event (published_at) WHERE published_at IS NOT NULL;

ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY outbox_event_tenant_isolation ON outbox_event
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON outbox_event TO isp_app;
GRANT UPDATE (published_at, attempts, last_error) ON outbox_event TO isp_app;


-- One row per (consumer, event) handled; written in the consumer's transaction.
CREATE TABLE shared_processed_event (
    consumer      text        NOT NULL,
    event_id      uuid        NOT NULL,
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    processed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE INDEX shared_processed_event_tenant_ix ON shared_processed_event (tenant_id, processed_at);

ALTER TABLE shared_processed_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY shared_processed_event_tenant_isolation ON shared_processed_event
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON shared_processed_event TO isp_app;
