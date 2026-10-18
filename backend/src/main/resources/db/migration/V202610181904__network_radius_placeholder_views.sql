-- PLACEHOLDER FreeRADIUS views (ADR 0004, N2). They have the final column shape but return no rows
-- yet; the real definitions join routers, subscriptions, packages and tenant policy and compute the
-- access profile at query time. See docs/06-network-access-control.md (FreeRADIUS design, Profiles
-- and reply attributes) and domain rules S2, S3, S4, T3, N3, N4.
--
-- These views are read by FreeRADIUS directly. Never ALTER them in place once real: publish a new
-- version (v_radius_auth_v2) and switch radius/ configuration to it (conventions.md, Migrations).
-- No RLS applies to FreeRADIUS: tenant isolation is the router-tenant join inside the view (T3).

-- rlm_sql client list: one row per router tunnel address on this node's WireGuard hub.
CREATE VIEW radius_client AS
SELECT
    NULL::bigint  AS id,
    NULL::uuid    AS tenant_id,
    NULL::text    AS nasname,       -- router tunnel IP
    NULL::text    AS shortname,
    'other'::text AS type,
    NULL::text    AS secret,        -- per-router shared secret, decrypted by a restricted function
    NULL::text    AS server,
    NULL::text    AS description
WHERE false;

-- rlm_sql check and reply attributes, keyed by username and the asking router (T3).
--   kind = 'check' rows feed authorize_check_query (e.g. Cleartext-Password),
--   kind = 'reply' rows feed authorize_reply_query (Mikrotik-Rate-Limit, Framed-Pool,
--   Mikrotik-Address-List, Session-Timeout).
CREATE VIEW v_radius_auth AS
SELECT
    NULL::bigint AS id,
    NULL::uuid   AS tenant_id,
    NULL::text   AS username,
    NULL::text   AS nasname,
    NULL::text   AS kind,
    NULL::text   AS attribute,
    NULL::text   AS op,
    NULL::text   AS value
WHERE false;

GRANT SELECT ON radius_client, v_radius_auth TO isp_radius;
