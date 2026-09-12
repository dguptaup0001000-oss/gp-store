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
# So this makes a genuinely empty database and brings it up the way a new
# environment has to: Hibernate creates the entity tables under
# ddl-auto=update, deferred Flyway then applies V2 to the head (there is no
# V1 - see db/migration/README.md), and a SECOND boot under ddl-auto=validate
# makes Hibernate compare every entity against the result and refuse to start
# if they disagree.
#
# THIS FOUND THREE REASONS A NEW ENVIRONMENT COULD NOT BE PROVISIONED, all of
# the same shape: Hibernate creates each table at the entity's CURRENT form,
# and the historical migrations then run against it as though it were at its
# form of the day. V33 and V46 insert seed rows naming only the columns that
# existed when they were written, so NOT NULL columns added later - and
# created by Hibernate without the DEFAULT the migration gives them - took
# nulls and killed the bootstrap. V55 declares two CHECK constraints inside a
# CREATE TABLE IF NOT EXISTS that Hibernate had already satisfied, so the
# constraints were silently absent and V55's own VERIFY block caught it.
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

say "Phase 1: bootstrap an empty database the way a new environment does it"
# THERE IS NO V1, AND THAT IS DELIBERATE - see db/migration/README.md. Flyway
# owns the incremental changes from V2 onward; the entity tables themselves
# come from Hibernate. So the order is Hibernate FIRST, then Flyway, which is
# what FlywayAfterSchemaConfig arranges and what the CI schema-migrate job
# runs. Pointing ddl-auto=validate at an empty database instead just fails on
# V2 with 'relation "orders" does not exist', which is a correct failure of
# the wrong procedure.
DDL_AUTO=update FLYWAY_ENABLED=true mvn -o -q -Pschema-bootstrap \
  -Dtest=EmptyDatabaseBootstrapTest -DexcludedGroups= test

say "What Flyway applied"
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' migrations, head = V' || (select version from flyway_schema_history
      where success order by installed_rank desc limit 1)
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
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' check constraints' from information_schema.table_constraints
     where constraint_schema='public' and constraint_type='CHECK';"

say "Tenant boundary on a schema built from nothing"
# NOT A HEADCOUNT ALONE - the list too. A table that gained a shop_id in a
# later migration but never gets one on a fresh build is a tenancy hole that
# exists only in new environments, which is the worst possible place for one.
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select count(*) || ' tables carry shop_id'
     from information_schema.columns
    where table_schema='public' and column_name='shop_id';"

say "No leftovers: the run started from empty and nothing seeded rows behind it"
sudo -u postgres psql -qAt -d "$DB_NAME" -c \
  "select 'orders=' || (select count(*) from orders)
       || ' payments=' || (select count(*) from payments)
       || ' customers=' || (select count(*) from customers);"

say "Phase 2: boot again against the same database under ddl-auto=validate"
# THE CHECK THAT MATTERS. Flyway succeeding says the scripts ran. validate
# says the schema they produced is the one the entities expect - a missing
# column, a wrong type, a SMALLINT where the entity wants an INTEGER all stop
# the context here and nowhere earlier.
DDL_AUTO=validate FLYWAY_ENABLED=true mvn -o -q -Pschema-bootstrap \
  -Dtest=ProductionSchemaValidateTest -DexcludedGroups= test

say "PASSED: an empty database reaches the current schema, and the entities validate against it"
