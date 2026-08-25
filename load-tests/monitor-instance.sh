#!/usr/bin/env bash
# Sample JVM RSS, Postgres backends, Redis, and (if possible) Hikari-adjacent
# pg activity while a load stage runs. Does not change application config.
set -euo pipefail

OUT="${1:-/tmp/gp-store-load-monitor.tsv}"
INTERVAL="${INTERVAL:-5}"
JAVA_PID="${JAVA_PID:-}"

if [ -z "$JAVA_PID" ]; then
  JAVA_PID="$(pgrep -f 'backend-0.0.1-SNAPSHOT.jar|com.gpstore.BackendApplication' | head -n1 || true)"
fi

echo -e "ts\tcpu_pct\tmem_used_mb\tjava_rss_mb\tpg_backends\tpg_active\tredis_ops\tredis_clients" > "$OUT"
echo "Writing $OUT every ${INTERVAL}s (java_pid=${JAVA_PID:-none})"

while true; do
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  cpu="$(vmstat 1 2 | tail -1 | awk '{print 100-$15}')"
  mem="$(free -m | awk '/Mem:/{print $3}')"
  rss="NA"
  if [ -n "$JAVA_PID" ] && [ -r "/proc/$JAVA_PID/status" ]; then
    rss="$(awk '/VmRSS:/{printf "%.0f", $2/1024}' "/proc/$JAVA_PID/status")"
  fi
  pg="$(PGPASSWORD="${PGPASSWORD:-gpstore_test_password}" psql -h localhost -U gpstore -d gpstore_test -Atqc "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database();" 2>/dev/null || echo NA)"
  pg_act="$(PGPASSWORD="${PGPASSWORD:-gpstore_test_password}" psql -h localhost -U gpstore -d gpstore_test -Atqc "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'active';" 2>/dev/null || echo NA)"
  redis_ops="$(redis-cli info stats 2>/dev/null | tr -d '\r' | awk -F: '/instantaneous_ops_per_sec/{print $2}' || echo NA)"
  redis_clients="$(redis-cli info clients 2>/dev/null | tr -d '\r' | awk -F: '/connected_clients/{print $2}' || echo NA)"
  echo -e "${ts}\t${cpu}\t${mem}\t${rss}\t${pg}\t${pg_act}\t${redis_ops}\t${redis_clients}" | tee -a "$OUT" >/dev/null
  echo "${ts} cpu=${cpu}% mem=${mem}MB java_rss=${rss}MB pg=${pg} active=${pg_act} redis_ops=${redis_ops} clients=${redis_clients}"
  sleep "$INTERVAL"
done
