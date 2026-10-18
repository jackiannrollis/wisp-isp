-- Test container only: give the application role a login before Flyway runs.
-- The password is a throwaway fake used nowhere else.
CREATE ROLE isp_app LOGIN PASSWORD 'isp_app_test_only';
