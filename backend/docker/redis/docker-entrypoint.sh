#!/bin/sh
# Build redis.conf from environment so the password is not on argv
# (visible via docker inspect / ps).
set -eu
CONF=/tmp/redis.conf
: "${REDIS_PASSWORD:?REDIS_PASSWORD must be set}"
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
