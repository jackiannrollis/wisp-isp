-- The synthetic RADIUS login probe (ADR 0019) reads the PPPoE passwords of the probe tenant's lines, and of
-- no other tenant's, through this view: the tenant id is a literal of the definition, not a parameter or a
-- setting, so isp_worker cannot widen it. T4 and ADR 0009 name it as the one other reader of line
-- passwords besides the RADIUS views. Like them it runs with its owner's rights over the tables.
--
-- expires_at tells the probe what to expect: a far-future expiry is FULL, one long past is RESTRICTED.
CREATE VIEW v_radius_probe_credential_v1 AS
SELECT
    s.id             AS subscription_id,
    s.pppoe_username AS username,
    k.secret         AS password,
    s.expires_at
FROM subscriptions_subscription s
JOIN network_radius_credential k ON k.tenant_id = s.tenant_id AND k.subscription_id = s.id
WHERE s.tenant_id = '0191f0be-0000-7000-8000-000000000001'::uuid
  AND s.state = 'ACTIVE'
  AND k.secret IS NOT NULL;

GRANT SELECT ON v_radius_probe_credential_v1 TO isp_worker;
