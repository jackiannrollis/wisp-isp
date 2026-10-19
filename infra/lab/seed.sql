-- Network lab fixtures (step 2, docs/09). DEVELOPER MACHINES ONLY. Run as isp_owner by
-- infra/lab/scripts/seed.(ps1|sh); safe to re-run: dates are reset relative to now().
--
-- Inputs come from the environment (never the command line, so the secret stays out of ps):
--   LAB_SECRET      RADIUS shared secret for the lab NAS rows (RADIUS_LAB_SECRET in infra/.env)
--   CHR_TUNNEL_IP   tunnel address of the lab MikroTik CHR on the WireGuard hub
--
-- Every PPPoE password is the obvious fake 'lab-password'. Defaults from context/open-questions.md
-- used here: Q4 (prepaid grace 0 days unless a row says otherwise), Q6 (exact expiry timestamps).
\set ON_ERROR_STOP on
\getenv lab_secret LAB_SECRET
\getenv chr_tunnel_ip CHR_TUNNEL_IP

BEGIN;

INSERT INTO tenancy_tenant (id, code, name, subdomain, status)
VALUES ('01900000-0000-7000-8000-000000000001', 'CB', 'Corebalt Lab ISP', 'cb', 'ACTIVE'),
       ('01900000-0000-7000-8000-000000000002', 'ZZ', 'Other Lab ISP', 'zz', 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

-- NAS rows: radtest inside the FreeRADIUS container (127.0.0.1) and the CHR over WireGuard, both
-- for tenant CB; tenant ZZ has its own radtest NAS on 127.0.0.2 for the T3 positive control.
INSERT INTO network_lab_router (id, tenant_id, name, tunnel_ip, lab_secret)
SELECT v.id::uuid, t.id, v.name, v.tunnel_ip::inet, :'lab_secret'
FROM (VALUES
        ('01900000-0000-7000-8000-0000000000a1', 'CB', 'cb-radtest',   '127.0.0.1'),
        ('01900000-0000-7000-8000-0000000000a2', 'CB', 'cb-lab-chr-1', :'chr_tunnel_ip'),
        ('01900000-0000-7000-8000-0000000000a3', 'ZZ', 'zz-radtest',   '127.0.0.2')
     ) AS v (id, code, name, tunnel_ip)
JOIN tenancy_tenant t ON t.code = v.code
ON CONFLICT (id) DO UPDATE
    SET tunnel_ip = EXCLUDED.tunnel_ip, lab_secret = EXCLUDED.lab_secret, updated_at = now();

-- One line per access profile (S2, docs/06).
INSERT INTO network_lab_line (id, tenant_id, username, lab_password, state, expires_at, grace_days,
                              grace_mode, rate_limit, throttle_rate_limit)
SELECT v.id::uuid, t.id, v.username, 'lab-password', v.state, now() + v.expires_in::interval,
       v.grace_days, v.grace_mode, '5M/10M', '1M/2M'
FROM (VALUES
        -- FULL, 30 days left: Session-Timeout capped at 86400 (S4)
        ('01900000-0000-7000-8000-0000000000b1', 'CB', 'CB000001', 'ACTIVE',     '30 days',  0, 'FULL_SPEED'),
        -- GRACE with throttled grace mode: THROTTLED
        ('01900000-0000-7000-8000-0000000000b2', 'CB', 'CB000002', 'ACTIVE',     '-1 hour',  3, 'THROTTLED'),
        -- GRACE with full-speed grace mode: FULL
        ('01900000-0000-7000-8000-0000000000b3', 'CB', 'CB000003', 'ACTIVE',     '-1 hour',  3, 'FULL_SPEED'),
        -- Past grace: RESTRICTED, accepted not rejected (N3)
        ('01900000-0000-7000-8000-0000000000b4', 'CB', 'CB000004', 'ACTIVE',     '-10 days', 2, 'FULL_SPEED'),
        -- PAUSED: RESTRICTED (S7)
        ('01900000-0000-7000-8000-0000000000b5', 'CB', 'CB000005', 'PAUSED',     '20 days',  0, 'FULL_SPEED'),
        -- REJECT profiles
        ('01900000-0000-7000-8000-0000000000b6', 'CB', 'CB000006', 'SUSPENDED',  '20 days',  0, 'FULL_SPEED'),
        ('01900000-0000-7000-8000-0000000000b7', 'CB', 'CB000007', 'PENDING',    '20 days',  0, 'FULL_SPEED'),
        -- FULL, expires in 2 hours with no grace: Session-Timeout about 7200 (S4)
        ('01900000-0000-7000-8000-0000000000b8', 'CB', 'CB000008', 'ACTIVE',     '2 hours',  0, 'FULL_SPEED'),
        -- Another tenant's customer: never authenticates through CB's routers (T3)
        ('01900000-0000-7000-8000-0000000000b9', 'ZZ', 'ZZ000001', 'ACTIVE',     '30 days',  0, 'FULL_SPEED')
     ) AS v (id, code, username, state, expires_in, grace_days, grace_mode)
JOIN tenancy_tenant t ON t.code = v.code
ON CONFLICT (id) DO UPDATE
    SET state = EXCLUDED.state, expires_at = EXCLUDED.expires_at, grace_days = EXCLUDED.grace_days,
        grace_mode = EXCLUDED.grace_mode, rate_limit = EXCLUDED.rate_limit,
        throttle_rate_limit = EXCLUDED.throttle_rate_limit, static_ip = NULL, updated_at = now();

COMMIT;

SELECT username, state, expires_at, grace_days, grace_mode FROM network_lab_line ORDER BY username;
