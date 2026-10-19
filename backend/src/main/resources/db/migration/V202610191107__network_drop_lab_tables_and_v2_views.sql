-- Step 3 retires the step-2 network lab tables and the views over them (decisions.md 2026-10-19).
-- radius/raddb/mods-available/sql reads radius_client_v3 and v_radius_auth_v3 from this release on;
-- nothing was ever deployed against v2, so no release needs both.
-- The placeholder radius_client and v_radius_auth stay: they hold no data and dropping them would
-- only churn the grants.

DROP VIEW v_radius_auth_v2;
DROP VIEW radius_client_v2;
DROP TABLE network_lab_line;
DROP TABLE network_lab_router;
