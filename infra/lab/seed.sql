-- Network lab fixtures (docs/09 steps 2 and 3). DEVELOPER MACHINES ONLY. Run as isp_owner by
-- infra/lab/scripts/seed.(ps1|sh), and by RadiusAuthIT; safe to re-run: dates are reset relative
-- to now(). Rows go straight into the real tables that v_radius_auth_v3 reads.
--
-- Inputs come from the environment (never the command line, so the secret stays out of ps):
--   LAB_SECRET      RADIUS shared secret for the lab NAS rows (RADIUS_LAB_SECRET in infra/.env,
--                   at least 12 characters)
--   CHR_TUNNEL_IP   tunnel address of the lab MikroTik CHR on the WireGuard hub
--
-- This is the only seeder of ACTIVE lines outside tests: it runs through `docker compose exec` into
-- the local stack's own postgres container, never against staging or production. Every PPPoE
-- password is the obvious fake 'lab-password'. Defaults from context/open-questions.md
-- used here: Q4 (prepaid grace 0 days unless a row says otherwise), Q6 (exact expiry timestamps).
-- Lines are given states directly; in the product only the subscription lifecycle (step 4) does that.
\set ON_ERROR_STOP on
\getenv lab_secret LAB_SECRET
\getenv chr_tunnel_ip CHR_TUNNEL_IP

BEGIN;

-- Tenant CB throttles during grace; tenant ZZ keeps full speed (S2, grace_mode).
INSERT INTO tenancy_tenant (id, code, name, subdomain, status, grace_mode)
VALUES ('01900000-0000-7000-8000-000000000001', 'CB', 'Corebalt Lab ISP', 'cb', 'ACTIVE', 'THROTTLED'),
       ('01900000-0000-7000-8000-000000000002', 'ZZ', 'Other Lab ISP', 'zz', 'ACTIVE', 'FULL_SPEED')
ON CONFLICT (code) DO UPDATE SET grace_mode = EXCLUDED.grace_mode, updated_at = now();

-- 10 Mbit/s down, 5 up: Mikrotik-Rate-Limit 5M/10M (N4). CB's package throttles to 1M/2M.
INSERT INTO packages_package (id, tenant_id, name, download_kbps, upload_kbps, duration_minutes,
                              price_minor, throttle_download_kbps, throttle_upload_kbps)
SELECT v.id::uuid, t.id, v.name, 10000, 5000, 43200, 250000, v.throttle_down, v.throttle_up
FROM (VALUES
        ('01900000-0000-7000-8000-0000000000e1', 'CB', 'Lab Home 10', 2000,           1000),
        ('01900000-0000-7000-8000-0000000000e2', 'ZZ', 'Lab Basic 10', NULL::integer, NULL::integer)
     ) AS v (id, code, name, throttle_down, throttle_up)
JOIN tenancy_tenant t ON t.code = v.code
ON CONFLICT (id) DO NOTHING;

-- NAS rows: radtest inside the FreeRADIUS container (127.0.0.1) and the CHR over WireGuard, both
-- for tenant CB; tenant ZZ has its own radtest NAS on 127.0.0.2 for the T3 positive control.
INSERT INTO network_router (id, tenant_id, name, tunnel_ip_a)
SELECT v.id::uuid, t.id, v.name, v.tunnel_ip::inet
FROM (VALUES
        ('01900000-0000-7000-8000-0000000000a1', 'CB', 'cb-radtest',   '127.0.0.1'),
        ('01900000-0000-7000-8000-0000000000a2', 'CB', 'cb-lab-chr-1', :'chr_tunnel_ip'),
        ('01900000-0000-7000-8000-0000000000a3', 'ZZ', 'zz-radtest',   '127.0.0.2')
     ) AS v (id, code, name, tunnel_ip)
JOIN tenancy_tenant t ON t.code = v.code
ON CONFLICT (id) DO UPDATE SET tunnel_ip_a = EXCLUDED.tunnel_ip_a, updated_at = now();

INSERT INTO network_radius_credential (id, tenant_id, router_id, secret)
SELECT ('01900000-0000-7000-8000-000000000d' || right(r.id::text, 2))::uuid, r.tenant_id, r.id, :'lab_secret'
FROM network_router r
WHERE r.id IN ('01900000-0000-7000-8000-0000000000a1', '01900000-0000-7000-8000-0000000000a2',
               '01900000-0000-7000-8000-0000000000a3')
ON CONFLICT (id) DO UPDATE SET secret = EXCLUDED.secret, updated_at = now();

-- One customer and line per access profile (S2, docs/06). Numbers are the PPPoE usernames (T2).
CREATE TEMP TABLE lab_line (
    n int, code text, number text, state text, expires_in interval, grace_days int, static_ip inet,
    paused_ago interval, pause_left interval
) ON COMMIT DROP;
INSERT INTO lab_line VALUES
    -- FULL, 30 days left: Session-Timeout capped at 86400 (S4)
    (1,  'CB', 'CB000001', 'ACTIVE',    '30 days',  0, NULL, NULL, NULL),
    -- GRACE in a THROTTLED tenant: THROTTLED, 1M/2M, list throttled
    (2,  'CB', 'CB000002', 'ACTIVE',    '-1 hour',  3, NULL, NULL, NULL),
    -- FULL with a static IP: Framed-IP-Address instead of a pool
    (3,  'CB', 'CB000003', 'ACTIVE',    '30 days',  0, '10.10.0.53', NULL, NULL),
    -- Past grace: RESTRICTED, accepted not rejected (N3)
    (4,  'CB', 'CB000004', 'ACTIVE',    '-10 days', 2, NULL, NULL, NULL),
    -- PAUSED: RESTRICTED (S7)
    (5,  'CB', 'CB000005', 'PAUSED',    '20 days',  0, NULL, '5 days', '10 days'),
    -- REJECT profiles
    (6,  'CB', 'CB000006', 'SUSPENDED', '20 days',  0, NULL, NULL, NULL),
    (7,  'CB', 'CB000007', 'PENDING',   NULL,       0, NULL, NULL, NULL),
    -- FULL, expires in 2 hours with no grace: Session-Timeout about 7200 (S4)
    (8,  'CB', 'CB000008', 'ACTIVE',    '2 hours',  0, NULL, NULL, NULL),
    -- Another tenant's customer: never authenticates through CB's routers (T3)
    (9,  'ZZ', 'ZZ000001', 'ACTIVE',    '30 days',  0, NULL, NULL, NULL),
    -- GRACE in a FULL_SPEED tenant: FULL
    (10, 'ZZ', 'ZZ000002', 'ACTIVE',    '-1 hour',  3, NULL, NULL, NULL),
    -- PAUSED, pause ended an hour ago and no job has recorded the resume: the view resumes it with
    -- the 10 paused days added back, so it is FULL, not RESTRICTED (S7, E4)
    (11, 'ZZ', 'ZZ000003', 'PAUSED',    '-2 days',  0, NULL, '10 days', '-1 hour');

INSERT INTO customers_customer (id, tenant_id, number, name, phone)
SELECT ('01900000-0000-7000-8000-0000000c' || lpad(to_hex(l.n), 4, '0'))::uuid, t.id, l.number,
       'Lab Customer ' || l.number, '+2547000000' || lpad(l.n::text, 2, '0')
FROM lab_line l JOIN tenancy_tenant t ON t.code = l.code
ON CONFLICT (id) DO NOTHING;

INSERT INTO customers_number_counter (tenant_id, last_value)
SELECT t.id, max(substr(l.number, 3)::int)
FROM lab_line l JOIN tenancy_tenant t ON t.code = l.code
GROUP BY t.id
ON CONFLICT (tenant_id) DO UPDATE
    SET last_value = greatest(customers_number_counter.last_value, EXCLUDED.last_value);

INSERT INTO subscriptions_subscription (id, tenant_id, customer_id, package_id, router_id,
                                        pppoe_username, state, expires_at, grace_days, static_ip,
                                        paused_at, pause_until)
SELECT ('01900000-0000-7000-8000-0000000b' || lpad(to_hex(l.n), 4, '0'))::uuid, t.id,
       ('01900000-0000-7000-8000-0000000c' || lpad(to_hex(l.n), 4, '0'))::uuid,
       CASE l.code WHEN 'CB' THEN '01900000-0000-7000-8000-0000000000e1'::uuid
                   ELSE '01900000-0000-7000-8000-0000000000e2'::uuid END,
       CASE l.code WHEN 'CB' THEN '01900000-0000-7000-8000-0000000000a2'::uuid
                   ELSE '01900000-0000-7000-8000-0000000000a3'::uuid END,
       l.number, l.state, now() + l.expires_in, l.grace_days, l.static_ip,
       now() - l.paused_ago, now() + l.pause_left
FROM lab_line l JOIN tenancy_tenant t ON t.code = l.code
ON CONFLICT (id) DO UPDATE
    SET state = EXCLUDED.state, expires_at = EXCLUDED.expires_at, grace_days = EXCLUDED.grace_days,
        static_ip = EXCLUDED.static_ip, paused_at = EXCLUDED.paused_at,
        pause_until = EXCLUDED.pause_until, terminated_at = NULL, updated_at = now();

INSERT INTO network_radius_credential (id, tenant_id, subscription_id, secret)
SELECT ('01900000-0000-7000-8000-0000000d' || lpad(to_hex(l.n), 4, '0'))::uuid, t.id,
       ('01900000-0000-7000-8000-0000000b' || lpad(to_hex(l.n), 4, '0'))::uuid, 'lab-password'
FROM lab_line l JOIN tenancy_tenant t ON t.code = l.code
ON CONFLICT (id) DO UPDATE SET secret = EXCLUDED.secret, updated_at = now();

COMMIT;

SELECT s.pppoe_username, s.state, s.expires_at, s.grace_days, s.pause_until, t.grace_mode
FROM subscriptions_subscription s JOIN tenancy_tenant t ON t.id = s.tenant_id
WHERE s.id::text LIKE '01900000-0000-7000-8000-0000000b%'
ORDER BY s.pppoe_username;
