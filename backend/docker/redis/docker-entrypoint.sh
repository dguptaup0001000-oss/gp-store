#!/bin/sh
# Build redis.conf from a Docker secret file (or REDIS_PASSWORD as a
# fallback for older layouts). The password is not on argv and not in
# container Env on current production Compose — docker inspect will not
# show requirepass or REDIS_PASSWORD.
#
# Start as root (Compose user: "0:0") so we can chown /data from an older
# UID, then drop to redis. cap_drop ALL would block CHOWN/SETUID.
set -eu
CONF=/tmp/redis.conf
if [ -n "${REDIS_PASSWORD_FILE:-}" ] && [ -r "$REDIS_PASSWORD_FILE" ]; then
  REDIS_PASSWORD="$(tr -d '\r\n' < "$REDIS_PASSWORD_FILE")"
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

if [ "$(id -u)" = 0 ] && id -u redis >/dev/null 2>&1; then
  # Volume files may be owned by a previous container UID. Do not delete
  # dump.rdb — chown is enough for Redis to load it.
  chown -R redis:redis /data 2>/dev/null || true
  chown redis:redis "$CONF"
  if command -v gosu >/dev/null 2>&1; then
    exec gosu redis redis-server "$CONF"
  fi
  if command -v su-exec >/dev/null 2>&1; then
    exec su-exec redis redis-server "$CONF"
  fi
fi
exec redis-server "$CONF"
