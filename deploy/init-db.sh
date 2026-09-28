#!/usr/bin/env bash
set -euo pipefail
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE wenfou_app LOGIN PASSWORD :'app_password';
GRANT USAGE ON SCHEMA public TO wenfou_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wenfou_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO wenfou_app;
SQL
