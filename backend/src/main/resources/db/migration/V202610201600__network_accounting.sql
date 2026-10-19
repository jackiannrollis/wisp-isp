-- RADIUS accounting (docs/06 Accounting, N2, N7). FreeRADIUS writes radacct, the only table it may
-- write; screens and reports never read radacct, only network_usage_daily (N7).
--
-- radacct is partitioned by month on acctstarttime; a DEFAULT partition catches anything outside the
-- created months. network_ensure_radacct_partitions creates the current month and the next ones
-- (moving any rows the DEFAULT partition holds for them), network_drop_radacct_partitions drops
-- months older than the retention (13 months). Both run as the owner (SECURITY DEFINER); the
-- application role may only call them.
--
-- The NAS is the router whose tunnel address sent the packet (FreeRADIUS writes
-- Packet-Src-IP-Address, never the client-asserted NAS-IP-Address; T3). A trigger fills tenant_id,
-- router_id and subscription_id from it, so FreeRADIUS needs no access to any other table.

CREATE TABLE radacct (
    radacctid           bigint      GENERATED ALWAYS AS IDENTITY,
    acctsessionid       text        NOT NULL,
    acctuniqueid        text        NOT NULL,
    username            text,
    realm               text,
    nasipaddress        inet        NOT NULL,
    nasportid           text,
    nasporttype         text,
    acctstarttime       timestamptz NOT NULL,
    acctupdatetime      timestamptz,
    acctstoptime        timestamptz,
    acctsessiontime     bigint,
    acctauthentic       text,
    connectinfo_start   text,
    connectinfo_stop    text,
    -- Octets with Acct-*-Gigawords folded in (gigawords << 32 + octets). Input is what the router
    -- received from the customer (upload), output what it sent (download).
    acctinputoctets     bigint      NOT NULL DEFAULT 0,
    acctoutputoctets    bigint      NOT NULL DEFAULT 0,
    calledstationid     text,
    callingstationid    text,
    acctterminatecause  text,
    servicetype         text,
    framedprotocol      text,
    framedipaddress     inet,
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    router_id           uuid,
    subscription_id     uuid,
    PRIMARY KEY (acctuniqueid, acctstarttime)
) PARTITION BY RANGE (acctstarttime);

CREATE TABLE radacct_default PARTITION OF radacct DEFAULT;

CREATE INDEX radacct_tenant_start_ix ON radacct (tenant_id, acctstarttime);
CREATE INDEX radacct_tenant_subscription_ix ON radacct (tenant_id, subscription_id, acctstarttime);
-- Open sessions: the stale-session job, the roll-up and Accounting-On.
CREATE INDEX radacct_open_ix ON radacct (tenant_id, (coalesce(acctupdatetime, acctstarttime))) WHERE acctstoptime IS NULL;
CREATE INDEX radacct_open_nas_ix ON radacct (nasipaddress) WHERE acctstoptime IS NULL;
CREATE INDEX radacct_stopped_ix ON radacct (tenant_id, acctstoptime) WHERE acctstoptime IS NOT NULL;

CREATE FUNCTION network_radacct_resolve() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    SELECT r.tenant_id, r.id INTO NEW.tenant_id, NEW.router_id
    FROM network_router r
    WHERE r.tunnel_ip_a = NEW.nasipaddress;
    IF NEW.tenant_id IS NULL THEN
        RAISE EXCEPTION 'radacct: no router has tunnel address %', NEW.nasipaddress;
    END IF;
    SELECT s.id INTO NEW.subscription_id
    FROM subscriptions_subscription s
    WHERE s.tenant_id = NEW.tenant_id AND s.pppoe_username = NEW.username;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION network_radacct_resolve() FROM PUBLIC;

CREATE TRIGGER radacct_resolve BEFORE INSERT ON radacct
    FOR EACH ROW EXECUTE FUNCTION network_radacct_resolve();

ALTER TABLE radacct ENABLE ROW LEVEL SECURITY;
CREATE POLICY radacct_tenant_isolation ON radacct
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- FreeRADIUS never sets a tenant: it writes for every tenant, through the trigger above (N2).
CREATE POLICY radacct_radius_writes ON radacct TO isp_radius USING (true) WITH CHECK (true);

-- N2: FreeRADIUS inserts sessions and updates them by acctuniqueid; it reads nothing else.
GRANT INSERT (acctsessionid, acctuniqueid, username, realm, nasipaddress, nasportid, nasporttype, acctstarttime,
              acctupdatetime, acctstoptime, acctsessiontime, acctauthentic, connectinfo_start, connectinfo_stop,
              acctinputoctets, acctoutputoctets, calledstationid, callingstationid, acctterminatecause, servicetype,
              framedprotocol, framedipaddress) ON radacct TO isp_radius;
GRANT SELECT (acctuniqueid, acctstarttime, acctstoptime, acctterminatecause, nasipaddress) ON radacct TO isp_radius;
GRANT UPDATE (acctupdatetime, acctstoptime, acctsessiontime, acctinputoctets, acctoutputoctets, acctterminatecause,
              connectinfo_stop, framedipaddress) ON radacct TO isp_radius;

-- The application reads sessions and closes stale ones; it never inserts or deletes accounting.
GRANT SELECT ON radacct TO isp_app;
GRANT UPDATE (acctstoptime, acctterminatecause) ON radacct TO isp_app;


CREATE FUNCTION network_ensure_radacct_partitions(months_ahead integer) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    month_start date;
    from_ts timestamptz;
    to_ts timestamptz;
    partition text;
    created integer := 0;
BEGIN
    IF months_ahead < 0 OR months_ahead > 12 THEN
        RAISE EXCEPTION 'months_ahead must be 0 to 12';
    END IF;
    FOR i IN 0..months_ahead LOOP
        month_start := (date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => i))::date;
        from_ts := month_start::timestamp AT TIME ZONE 'UTC';
        to_ts := (month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC';
        partition := 'radacct_p' || to_char(month_start, 'YYYYMM');
        IF to_regclass(partition) IS NULL THEN
            EXECUTE format('CREATE TABLE %I (LIKE radacct INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', partition);
            -- Rows that reached DEFAULT before this month existed move first, or ATTACH would fail.
            EXECUTE format('WITH moved AS (DELETE FROM radacct_default WHERE acctstarttime >= %L AND acctstarttime < %L '
                           'RETURNING *) INSERT INTO %I SELECT * FROM moved', from_ts, to_ts, partition);
            EXECUTE format('ALTER TABLE radacct ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)', partition, from_ts, to_ts);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END
$$;
REVOKE ALL ON FUNCTION network_ensure_radacct_partitions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION network_ensure_radacct_partitions(integer) TO isp_app;

CREATE FUNCTION network_drop_radacct_partitions(keep_months integer) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    cutoff date;
    child record;
    dropped integer := 0;
BEGIN
    IF keep_months < 13 THEN
        RAISE EXCEPTION 'radacct is kept at least 13 months (N7)';
    END IF;
    cutoff := (date_trunc('month', now() AT TIME ZONE 'UTC') - make_interval(months => keep_months))::date;
    FOR child IN
        SELECT c.relname
        FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'radacct'::regclass AND c.relname ~ '^radacct_p[0-9]{6}$'
    LOOP
        IF to_date(substr(child.relname, 10), 'YYYYMM') < cutoff THEN
            EXECUTE format('DROP TABLE %I', child.relname);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    DELETE FROM radacct_default WHERE acctstarttime < cutoff::timestamp AT TIME ZONE 'UTC';
    RETURN dropped;
END
$$;
REVOKE ALL ON FUNCTION network_drop_radacct_partitions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION network_drop_radacct_partitions(integer) TO isp_app;

SELECT network_ensure_radacct_partitions(3);


-- Usage roll-up (N7). network_usage_session keeps, per session, the octets already counted (the
-- high-water mark); each run adds the growth since the last run to the tenant-local day it runs
-- in. network_usage_daily is the only usage source for screens and reports.
CREATE TABLE network_usage_session (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    acctuniqueid     text        NOT NULL,
    acctstarttime    timestamptz NOT NULL,
    subscription_id  uuid        NOT NULL,
    input_octets     bigint      NOT NULL,
    output_octets    bigint      NOT NULL,
    last_seen_at     timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, acctuniqueid, acctstarttime)
);

CREATE INDEX network_usage_session_seen_ix ON network_usage_session (tenant_id, last_seen_at);

ALTER TABLE network_usage_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_usage_session_tenant_isolation ON network_usage_session
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON network_usage_session TO isp_app;

CREATE TABLE network_usage_daily (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    subscription_id  uuid        NOT NULL,
    day              date        NOT NULL,
    input_octets     bigint      NOT NULL DEFAULT 0,
    output_octets    bigint      NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, subscription_id, day),
    CONSTRAINT network_usage_daily_octets_ck CHECK (input_octets >= 0 AND output_octets >= 0)
);

CREATE INDEX network_usage_daily_tenant_day_ix ON network_usage_daily (tenant_id, day);

ALTER TABLE network_usage_daily ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_usage_daily_tenant_isolation ON network_usage_daily
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON network_usage_daily TO isp_app;
