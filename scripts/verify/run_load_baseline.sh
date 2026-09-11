#!/usr/bin/env bash
#
# Boots the backend, measures it, and writes down what the machine was doing
# while it was measured.
#
# WHY THE MACHINE METRICS MATTER AS MUCH AS THE LATENCIES. "p95 was 400 ms"
# means nothing without "and the box was at 100% CPU with the load generator
# on it". A number taken from a saturated host is a floor, not a capacity, and
# the only way to report it honestly is to record the saturation alongside it.
#
# Usage:  scripts/verify/run_load_baseline.sh [levels] [requests-per-level]
set -euo pipefail

LEVELS="${1:-100,250,500,1000}"
REQUESTS="${2:-2000}"
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${LOAD_OUT_DIR:-/tmp/gp-load}"
mkdir -p "$OUT"

export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/gpstore_test}"
export DB_USERNAME="${DB_USERNAME:-gpstore}"
export DB_PASSWORD="${DB_PASSWORD:-gpstore_dev_password}"

echo "== machine =="
nproc
free -m | head -2
tee "$OUT/machine.txt" >/dev/null <<EOF
cpus=$(nproc)
memory_mb=$(free -m | awk '/^Mem:/{print $2}')
note=the load generator runs on this same machine
EOF

echo "== booting backend =="
cd "$HERE/backend"
java -jar target/*.jar > "$OUT/app.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 90); do
  if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 \
     || curl -sf http://localhost:8080/api/marketplace/mode >/dev/null 2>&1; then
    echo "up"
    break
  fi
  sleep 2
done

echo "== sampling the machine while the load runs =="
( while true; do
    printf '%s cpu_idle=%s ' "$(date +%s)" \
      "$(top -bn1 | awk '/Cpu\(s\)/{print $8}')"
    printf 'rss_mb=%s ' "$(ps -o rss= -p $APP_PID 2>/dev/null | awk '{print int($1/1024)}')"
    printf 'pg_conns=%s\n' \
      "$(sudo -u postgres psql -qAt -c 'select count(*) from pg_stat_activity;' 2>/dev/null)"
    sleep 5
  done ) > "$OUT/machine_samples.txt" 2>/dev/null &
SAMPLER=$!
trap 'kill $APP_PID $SAMPLER 2>/dev/null || true' EXIT

echo "== load =="
python3 "$HERE/scripts/verify/marketplace_load.py" \
  --levels "$LEVELS" --requests "$REQUESTS" \
  --out "$OUT/load.json" | tee "$OUT/load.ndjson"

kill $SAMPLER 2>/dev/null || true

echo
echo "== while it ran =="
echo "lowest CPU idle seen:"
awk '{for(i=1;i<=NF;i++) if($i ~ /^cpu_idle=/){sub("cpu_idle=","",$i); print $i}}' \
  "$OUT/machine_samples.txt" | sort -n | head -1
echo "peak app RSS (MB):"
awk '{for(i=1;i<=NF;i++) if($i ~ /^rss_mb=/){sub("rss_mb=","",$i); print $i}}' \
  "$OUT/machine_samples.txt" | sort -n | tail -1
echo "peak postgres connections:"
awk '{for(i=1;i<=NF;i++) if($i ~ /^pg_conns=/){sub("pg_conns=","",$i); print $i}}' \
  "$OUT/machine_samples.txt" | sort -n | tail -1
echo
echo "results: $OUT/load.json, samples: $OUT/machine_samples.txt, app log: $OUT/app.log"
