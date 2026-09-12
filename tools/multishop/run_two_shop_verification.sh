#!/bin/bash
# One command: a brand-new MULTI_SHOP_PRODUCTION deployment, two shops, and
# the whole verification against it.
#
# FROM AN EMPTY DATABASE EVERY TIME. A marketplace built on top of a previous
# run's leftovers is not a marketplace anybody would recognise, and shop ids
# that shift between runs make a failure impossible to read.
set -e
DB=postgresql://gpstore:gpstore_test_password@localhost:5432
JAR=${JAR:-/home/user/gp-store/backend/target/backend-0.0.1-SNAPSHOT.jar}
PORT=${PORT:-8090}
LOGS=${LOGS:-/tmp/two-shop}
mkdir -p "$LOGS"

env_for() {
  export DB_URL=jdbc:postgresql://localhost:5432/gpstore_mshop
  export DB_USERNAME=gpstore DB_PASSWORD=gpstore_test_password
  export JWT_SECRET=local-test-not-a-real-secret-0123456789abcdef
  export SERVER_PORT=$PORT RATE_LIMIT_AUTH_PER_MINUTE=100000
  export DDL_AUTO=$1
}

pkill -f "SNAPSHOT.jar" 2>/dev/null || true
# A dropped database with connections still open is a database that does not
# drop. Wait for the old server's pool to actually let go rather than racing it.
until [ "$(psql "$DB/postgres" -qtAc "select count(*) from pg_stat_activity where datname='gpstore_mshop'")" = "0" ]; do
  sleep 2
done
psql "$DB/postgres" -q -c "DROP DATABASE IF EXISTS gpstore_mshop;" \
                    -c "CREATE DATABASE gpstore_mshop OWNER gpstore;"

# The documented production bootstrap: once with ddl-auto=update to build the
# schema, then the real run under validate.
( env_for update; timeout 200 java -jar "$JAR" --platform.mode=MULTI_SHOP_PRODUCTION \
    > "$LOGS/bootstrap.log" 2>&1 ) || true
grep -q "Started BackendApplication" "$LOGS/bootstrap.log" || { echo "bootstrap failed"; exit 1; }

( env_for validate; nohup java -jar "$JAR" --platform.mode=MULTI_SHOP_PRODUCTION \
    > "$LOGS/server.log" 2>&1 & )
until curl -sf --noproxy '*' "http://localhost:$PORT/v1/api/marketplace/mode" >/dev/null 2>&1; do sleep 3; done

env -u HTTPS_PROXY -u https_proxy -u HTTP_PROXY -u ALL_PROXY \
  python3 "$(dirname "$0")/two_shop_verification.py"
