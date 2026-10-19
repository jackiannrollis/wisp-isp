-- Router monitoring (docs/06 Monitoring, N6) and the tunnel worker's role (ADR 0011).
--
-- A router is online when its RouterOS REST API answers /system/resource over the tunnel; three
-- missed polls in a row (60 s apart) make it OFFLINE. Accounting activity is only a secondary
-- "last heard" (last_accounting_at). The REST credential is envelope-encrypted (T4) and written by
-- the API; only the tunnel worker decrypts it, to poll.

ALTER TABLE network_router
    ADD COLUMN rest_username       text,
    ADD COLUMN rest_password_enc   bytea,
    ADD COLUMN rest_key_version    integer,
    ADD COLUMN status              text        NOT NULL DEFAULT 'NOT_MONITORED',
    ADD COLUMN poll_misses         integer     NOT NULL DEFAULT 0,
    ADD COLUMN last_polled_at      timestamptz,
    ADD COLUMN last_seen_at        timestamptz,
    ADD COLUMN offline_since       timestamptz,
    ADD COLUMN last_poll_error     text,
    ADD COLUMN last_accounting_at  timestamptz,
    ADD COLUMN routeros_version    text,
    ADD COLUMN board_name          text,
    ADD COLUMN uptime_seconds      bigint,
    ADD COLUMN cpu_load            integer,
    ADD COLUMN free_memory_bytes   bigint,
    ADD COLUMN total_memory_bytes  bigint,
    ADD CONSTRAINT network_router_status_ck CHECK (status IN ('NOT_MONITORED', 'UNKNOWN', 'ONLINE', 'OFFLINE')),
    ADD CONSTRAINT network_router_rest_ck
        CHECK ((rest_password_enc IS NULL) = (rest_key_version IS NULL) AND (rest_password_enc IS NULL) = (rest_username IS NULL));


-- The tunnel worker (ADR 0011) connects as isp_worker: no HTTP API, and only these grants, which
-- DatabaseGrantsIT pins exactly. LOGIN and its password are set per environment, like isp_app.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'isp_worker') THEN
        CREATE ROLE isp_worker NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO isp_worker;
-- Visit every active tenant (TenantIterator).
GRANT SELECT (id, status) ON tenancy_tenant TO isp_worker;
-- Unwrap the tenant data key to decrypt the router's REST credential (T4).
GRANT SELECT ON tenancy_tenant_data_key TO isp_worker;
-- Poll: who to poll and how; record the outcome.
GRANT SELECT (id, tenant_id, name, tunnel_ip_a, rest_username, rest_password_enc, rest_key_version, status, poll_misses,
              offline_since) ON network_router TO isp_worker;
GRANT UPDATE (status, poll_misses, last_polled_at, last_seen_at, offline_since, last_poll_error, routeros_version, board_name,
              uptime_seconds, cpu_load, free_memory_bytes, total_memory_bytes) ON network_router TO isp_worker;
-- RouterOffline and RouterOnline go out through the outbox (E1); the API's relay publishes them.
GRANT INSERT ON outbox_event TO isp_worker;
-- Idempotent consumption of RouterPollRequested (E2).
GRANT INSERT ON shared_processed_event TO isp_worker;

