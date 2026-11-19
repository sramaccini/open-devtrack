#!/bin/sh
# Run by the postgres image on the first start only, on an empty volume. The databases of OpenDevTrack and of Keycloak,
# each owned by a role of its own that is neither a superuser nor BYPASSRLS (asvs.md «The installation»): the role
# postgres stays for the administration of the server.
set -eu

psql -v ON_ERROR_STOP=1 -U postgres -v pw="$DB_PASSWORD" <<'SQL'
CREATE ROLE opendevtrack LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'pw';
CREATE DATABASE opendevtrack OWNER opendevtrack;
SQL

if [ -n "${KEYCLOAK_DB_PASSWORD:-}" ]; then
  psql -v ON_ERROR_STOP=1 -U postgres -v pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'pw';
CREATE DATABASE keycloak OWNER keycloak;
SQL
fi
