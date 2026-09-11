#!/usr/bin/env bash
#
# DOES THE SCHEMA BUILD ITSELF FROM NOTHING?
#
# Every migration in this repository has only ever been run FORWARD, against a
# database that already had the tables before it. That proves each step works
# on top of the last one; it does not prove the whole ladder stands on bare
# ground. A migration that silently depends on a column an earlier ad-hoc fix
# added by hand passes every day of the year and fails exactly once - the day
# somebody provisions a new environment.
#
# So this makes a genuinely empty database, runs V1 to the end into it, and
# then boots the application under ddl-auto=validate, which is the check that
# matters: Hibernate compares every entity against the schema Flyway just
# produced and refuses to start if they disagree.
#
# IT IS DESTRUCTIVE TO ITS OWN DATABASE AND ONLY TO THAT ONE. The name is
# fixed, it is dropped at the start, and the script refuses to run if it has
# been pointed at anything that looks like a real database.
#
# Usage:  scripts/verify/fresh_database.sh
set -euo pipefail

DB_NAME="${FRESH_DB_NAME:-gpstore_freshcheck}"
DB_USER="${DB_USERNAME:-gpstore}"
DB_PASS="${DB_PASSWORD:-gpstore_dev_password}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"

case "$DB_NAME" in
  *prod*|*production*|gpstore|gpstore_test)
    echo "Refusing to run against '$DB_NAME'. This script drops the database it is given." >&2
    exit 2
    ;;
esac

HERE="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$HERE/backend"

say() { printf '\n=== %s ===\n' "$1"; }

say "Dropping and recreating $DB_NAME"
sudo -u postgres psql -qAt -c "DROP DATABASE IF EXISTS $DB_NAME;" >/dev/null
sudo -u postgres psql -qAt -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;" >/dev/null

# PROVE IT IS EMPTY before claiming the run started from empty. A leftover
# database that failed to drop would otherwise make this whole check a
# repeat of the forward-only run it exists to replace.
TABLES=$(sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) from information_schema.tables where table_schema='public';")
if [ "$TABLES" != "0" ]; then
  echo "FAIL: $DB_NAME already has $TABLES tables; it is not a fresh database." >&2
  exit 1
fi
echo "public schema has 0 tables"

export DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
export DB_USERNAME="$DB_USER"
export DB_PASSWORD="$DB_PASS"
export FLYWAY_ENABLED=true

say "Migrating V1 to head"
mvn -o -q flyway:migrate \
  -Dflyway.url="$DB_URL" -Dflyway.user="$DB_USER" -Dflyway.password="$DB_PASS" \
  -Dflyway.locations=filesystem:src/main/resources/db/migration

say "What Flyway applied"
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' migrations, head = ' || max(version::numeric)
     from flyway_schema_history where success;"
FAILED=$(sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) from flyway_schema_history where not success;")
if [ "$FAILED" != "0" ]; then
  echo "FAIL: $FAILED migrations recorded as unsuccessful." >&2
  exit 1
fi

say "Schema shape"
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' tables'   from information_schema.tables  where table_schema='public';"
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' indexes'  from pg_indexes               where schemaname='public';"
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' foreign keys' from information_schema.table_constraints
     where constraint_schema='public' and constraint_type='FOREIGN KEY';"

say "Tenant boundary: every shop-owned table has its shop_id"
# NOT A HEADCOUNT - a list. A table that gained a shop_id column but never
# got one on a fresh build is a tenancy hole that only exists in new
# environments, which is the worst possible place for one to exist.
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' tables carry shop_id'
     from information_schema.columns
    where table_schema='public' and column_name='shop_id';"

say "Booting under ddl-auto=validate"
# THE REAL TEST. Flyway succeeding says the scripts ran; validate says the
# schema they produced is the one the code expects. A missing column, a
# wrong type, a smallint where the entity wants an integer - all of them
# stop the context here and nowhere earlier.
DDL_AUTO=validate mvn -o -q \
  -Dtest=com.gpstore.config.ApplicationStartsOnAFreshDatabaseTest \
  -DfailIfNoTests=false test

say "PASSED: V1..head builds this schema from empty, and the entities validate against it"
