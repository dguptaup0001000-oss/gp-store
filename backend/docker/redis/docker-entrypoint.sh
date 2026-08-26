#!/bin/sh
# Build redis.conf from a Docker secret file (or REDIS_PASSWORD as a
# fallback for older layouts). The password is not on argv and not in
# container Env on current production Compose — docker inspect will not
# show requirepass or REDIS_PASSWORD.
set -eu
CONF=/tmp/redis.conf
if [ -n "${REDIS_PASSWORD_FILE:-}" ] && [ -f "$REDIS_PASSWORD_FILE" ]; then
  REDIS_PASSWORD="$(cat "$REDIS_PASSWORD_FILE")"
fi
: "${REDIS_PASSWORD:?REDIS_PASSWORD or REDIS_PASSWORD_FILE must be set}"
{
  echo "bind 0.0.0.0"
  echo "protected-mode yes"
  echo "requirepass ${REDIS_PASSWORD}"
  echo "maxmemory 512mb"
  echo "maxmemory-policy allkeys-lru"
  echo "save 60 1000"
  echo "dir /data"
} > "$CONF"
chmod 600 "$CONF"
exec redis-server "$CONF"
