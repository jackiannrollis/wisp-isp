-- Database roles (conventions.md, Database).
--   isp_app     the application role: never owns tables, so row-level security always applies to it.
--   isp_radius  FreeRADIUS: select on radius_client and v_radius_auth, insert/update on radacct only (N2).
-- Roles are created NOLOGIN here so migrations work everywhere; LOGIN and passwords are set
-- per environment (infra/postgres/init for local, the secret store in production), never here.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'isp_app') THEN
        CREATE ROLE isp_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'isp_radius') THEN
        CREATE ROLE isp_radius NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO isp_app, isp_radius;
