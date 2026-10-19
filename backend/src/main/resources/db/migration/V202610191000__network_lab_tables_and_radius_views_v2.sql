-- Network lab, step 2 of the v1 build order (docs/09): FreeRADIUS reads a hand-written
-- v_radius_auth_v2 from two LAB tables that stand in for routers and subscriptions until step 3.
-- Step 3 adds the real network, customers and subscriptions tables, publishes v_radius_auth_v3 /
-- radius_client_v3 over them, switches radius/ to v3 and drops these lab tables.
--
-- The access-profile logic below is the real one (S2, S4, N3, N4) and is what step 4's
-- AccessPolicy must agree with (S3). Views are never altered in place: a change is a new version
-- and a switch in radius/raddb/mods-available/sql (conventions.md, Migrations).
--
-- Lab rows are not part of any migration; infra/lab/seed.sql loads obvious fakes on a developer
-- machine. isp_app gets no grants: no application code reads these tables.

-- Stand-in for network routers. LAB ONLY: the shared secret is plaintext; real routers store it
-- envelope-encrypted (secret_enc, key_version) and the view decrypts it through a restricted
-- function (T4, docs/06 FreeRADIUS design).
CREATE TABLE network_lab_router (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    name        text        NOT NULL,
    tunnel_ip   inet        NOT NULL,
    lab_secret  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT network_lab_router_tunnel_ip_uk UNIQUE (tunnel_ip)
);

CREATE INDEX network_lab_router_tenant_ix ON network_lab_router (tenant_id);

-- Stand-in for a PPPoE line: the subscription fields the view needs, with the tenant's grace mode
-- copied on (tenant billing policy arrives with steps 3 and 4). LAB ONLY: plaintext password; real
-- passwords are encrypted and decrypted into the view by a restricted function (docs/06).
CREATE TABLE network_lab_line (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    username             text        NOT NULL,
    lab_password         text        NOT NULL,
    state                text        NOT NULL,
    expires_at           timestamptz NOT NULL,
    grace_days           integer     NOT NULL DEFAULT 0,
    grace_mode           text        NOT NULL DEFAULT 'FULL_SPEED',
    rate_limit           text        NOT NULL,
    throttle_rate_limit  text        NOT NULL,
    static_ip            inet,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    -- T2: usernames are unique across the platform and prefixed with the tenant code.
    CONSTRAINT network_lab_line_username_uk UNIQUE (username),
    CONSTRAINT network_lab_line_username_ck CHECK (username ~ '^[A-Z]{2,4}[0-9]{6}(-[0-9]+)?$'),
    -- S1: only stored states.
    CONSTRAINT network_lab_line_state_ck CHECK (state IN ('PENDING', 'ACTIVE', 'PAUSED', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT network_lab_line_grace_days_ck CHECK (grace_days >= 0),
    CONSTRAINT network_lab_line_grace_mode_ck CHECK (grace_mode IN ('FULL_SPEED', 'THROTTLED')),
    -- N4: upload/download from the router's point of view, e.g. 5M/10M.
    CONSTRAINT network_lab_line_rate_limit_ck CHECK (rate_limit ~ '^[0-9]+[kM]?/[0-9]+[kM]?$'),
    CONSTRAINT network_lab_line_throttle_rate_limit_ck CHECK (throttle_rate_limit ~ '^[0-9]+[kM]?/[0-9]+[kM]?$')
);

CREATE INDEX network_lab_line_tenant_ix ON network_lab_line (tenant_id);

-- T1: tenant-scoped like every other table. The views below run as their owner and are the only
-- readers, so FreeRADIUS (which has no tenant context) is isolated by the router join instead (T3).
ALTER TABLE network_lab_router ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_lab_router_tenant_isolation ON network_lab_router
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE network_lab_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY network_lab_line_tenant_isolation ON network_lab_line
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);


-- rlm_sql client list (N2): one row per router tunnel address. FreeRADIUS needs a positive integer id.
CREATE VIEW radius_client_v2 AS
SELECT
    row_number() OVER (ORDER BY r.created_at, r.id) AS id,
    r.tenant_id,
    host(r.tunnel_ip)                               AS nasname,
    r.name                                          AS shortname,
    'other'::text                                   AS type,
    r.lab_secret                                    AS secret,
    NULL::text                                      AS server,
    'router ' || r.id::text                         AS description
FROM network_lab_router r;

-- rlm_sql check and reply attributes per username and asking router.
--
-- Profile (S2), evaluated in this order at query time:
--   PENDING, SUSPENDED, TERMINATED          -> REJECT
--   PAUSED                                  -> RESTRICTED
--   now < expires_at                        -> FULL
--   now < expires_at + grace_days           -> FULL, or THROTTLED when grace_mode = THROTTLED
--   otherwise                               -> RESTRICTED
-- Reply attributes (docs/06, N3, N4):
--   FULL        Mikrotik-Rate-Limit = rate_limit, Framed-Pool = full (or Framed-IP-Address)
--   THROTTLED   throttle_rate_limit, Framed-Pool = full (or Framed-IP-Address), Address-List throttled
--   RESTRICTED  512k/512k, Framed-Pool = restricted, Address-List restricted, Session-Timeout 86400
--   REJECT      Auth-Type := Reject and a Reply-Message with the reason
-- Session-Timeout (S4): seconds to the next profile change, capped at 86400. Before expiry the next
-- change is expires_at when grace is throttled or zero, otherwise the end of grace (docs/04).
-- T3: a line only appears with the tunnel addresses of its own tenant's routers.
CREATE VIEW v_radius_auth_v2 AS
WITH line AS (
    SELECT
        l.*,
        l.expires_at + make_interval(days => l.grace_days) AS grace_ends_at,
        CASE
            WHEN l.state IN ('PENDING', 'SUSPENDED', 'TERMINATED') THEN 'REJECT'
            WHEN l.state = 'PAUSED' THEN 'RESTRICTED'
            WHEN now() < l.expires_at THEN 'FULL'
            WHEN now() < l.expires_at + make_interval(days => l.grace_days)
                THEN CASE l.grace_mode WHEN 'THROTTLED' THEN 'THROTTLED' ELSE 'FULL' END
            ELSE 'RESTRICTED'
        END AS profile
    FROM network_lab_line l
),
timed AS (
    SELECT
        line.*,
        CASE
            WHEN line.profile = 'FULL' AND now() < line.expires_at
                 AND (line.grace_mode = 'THROTTLED' OR line.grace_days = 0) THEN line.expires_at
            WHEN line.profile IN ('FULL', 'THROTTLED') THEN line.grace_ends_at
        END AS next_change_at
    FROM line
),
attrs AS (
    SELECT
        t.tenant_id,
        t.username,
        host(r.tunnel_ip) AS nasname,
        a.ord,
        a.kind,
        a.attribute,
        a.value
    FROM timed t
    JOIN network_lab_router r ON r.tenant_id = t.tenant_id
    CROSS JOIN LATERAL (VALUES
        (1, 'check', 'Cleartext-Password',
            CASE WHEN t.profile <> 'REJECT' THEN t.lab_password END),
        (2, 'check', 'Auth-Type',
            CASE WHEN t.profile = 'REJECT' THEN 'Reject' END),
        (3, 'reply', 'Reply-Message',
            CASE t.state
                WHEN 'PENDING' THEN 'Account not yet active'
                WHEN 'SUSPENDED' THEN 'Account suspended'
                WHEN 'TERMINATED' THEN 'Account terminated'
            END),
        (4, 'reply', 'Mikrotik-Rate-Limit',
            CASE t.profile
                WHEN 'FULL' THEN t.rate_limit
                WHEN 'THROTTLED' THEN t.throttle_rate_limit
                WHEN 'RESTRICTED' THEN '512k/512k'
            END),
        (5, 'reply', 'Framed-IP-Address',
            CASE WHEN t.profile IN ('FULL', 'THROTTLED') THEN host(t.static_ip) END),
        (6, 'reply', 'Framed-Pool',
            CASE
                WHEN t.profile = 'RESTRICTED' THEN 'restricted'
                WHEN t.profile IN ('FULL', 'THROTTLED') AND t.static_ip IS NULL THEN 'full'
            END),
        (7, 'reply', 'Mikrotik-Address-List',
            CASE t.profile WHEN 'THROTTLED' THEN 'throttled' WHEN 'RESTRICTED' THEN 'restricted' END),
        (8, 'reply', 'Session-Timeout',
            CASE
                WHEN t.profile = 'REJECT' THEN NULL
                WHEN t.next_change_at IS NULL THEN '86400'
                ELSE least(86400, greatest(1, ceil(extract(epoch FROM t.next_change_at - now()))))::bigint::text
            END)
    ) AS a (ord, kind, attribute, value)
    WHERE a.value IS NOT NULL
)
SELECT
    ord::bigint AS id,
    tenant_id,
    username,
    nasname,
    kind,
    attribute,
    ':='::text  AS op,
    value
FROM attrs;

GRANT SELECT ON radius_client_v2, v_radius_auth_v2 TO isp_radius;
