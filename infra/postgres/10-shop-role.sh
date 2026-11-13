#!/bin/sh
set -eu

psql --quiet --no-psqlrc --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=shop_user="$SHOP_DB_USER" --set=shop_password="$SHOP_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I WITH LOGIN PASSWORD %L', :'shop_user', :'shop_password') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', current_database(), :'shop_user') \gexec
SQL
