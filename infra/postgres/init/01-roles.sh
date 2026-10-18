#!/bin/sh
# Runs once, when the PostgreSQL volume is first created (local development only).
#
#   isp_owner   owns the schema; Flyway runs as this role. Not a superuser.
#   isp_app     the API's role; owns nothing, so row-level security applies to it.
#   isp_radius  FreeRADIUS; select on radius_client and v_radius_auth only (granted by Flyway).
#
# Passwords come from infra/.env. Flyway's first migration creates the roles NOLOGIN when they
# do not exist (other environments); here they are created first with LOGIN.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v owner_password="$ISP_DB_OWNER_PASSWORD" \
    -v app_password="$ISP_DB_APP_PASSWORD" \
    -v radius_password="$RADIUS_DB_PASSWORD" <<'SQL'
CREATE ROLE isp_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE isp_app LOGIN PASSWORD :'app_password';
CREATE ROLE isp_radius LOGIN PASSWORD :'radius_password';

CREATE DATABASE isp OWNER isp_owner;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname isp <<'SQL'
ALTER SCHEMA public OWNER TO isp_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE isp FROM PUBLIC;
GRANT CONNECT ON DATABASE isp TO isp_app, isp_radius;
SQL
