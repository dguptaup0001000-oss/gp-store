#!/usr/bin/env bash
# Started by systemd (see gpstore-backend.service). Do not run as root.
set -euo pipefail

ENV_FILE="${GPSTORE_ENV_FILE:-/opt/gpstore/env.production}"
JAR="${GPSTORE_JAR:-/opt/gpstore/backend.jar}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "missing $ENV_FILE" >&2
  exit 1
fi
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"

exec /usr/bin/java \
  -XX:MaxRAMPercentage="${JVM_MAX_RAM_PERCENT:-35}" \
  -XX:MaxMetaspaceSize="${JVM_MAX_METASPACE:-256m}" \
  -XX:ReservedCodeCacheSize="${JVM_CODE_CACHE:-64m}" \
  -XX:MaxDirectMemorySize="${JVM_MAX_DIRECT:-32m}" \
  -Xss"${JVM_THREAD_STACK:-512k}" \
  -XX:ActiveProcessorCount="${JVM_ACTIVE_PROCESSORS:-2}" \
  ${JVM_GC:--XX:+UseG1GC} \
  ${JVM_OOM_BEHAVIOUR:--XX:+ExitOnOutOfMemoryError} \
  -Djava.security.egd=file:/dev/./urandom \
  -jar "$JAR"
