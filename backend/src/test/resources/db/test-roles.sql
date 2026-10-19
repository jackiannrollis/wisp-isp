-- Test container only: give the application and FreeRADIUS roles a login before Flyway runs.
-- The passwords are throwaway fakes used nowhere else.
CREATE ROLE isp_app LOGIN PASSWORD 'isp_app_test_only';
CREATE ROLE isp_radius LOGIN PASSWORD 'isp_radius_test_only';
CREATE ROLE isp_worker LOGIN PASSWORD 'isp_worker_test_only';
