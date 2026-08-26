#!/bin/sh
# Runtime entrypoint. Secrets come from the container environment or a
# Docker secret file, not this file.
set -eu
# -r: a 0600 root-owned secret exists but appuser cannot read it; do not
# crash the shell (set -e) before Java can report a clear error.
if [ -n "${REDIS_PASSWORD_FILE:-}" ] && [ -r "$REDIS_PASSWORD_FILE" ]; then
  # Strip CR/LF so requirepass and Spring see the same value. Do not log it.
  REDIS_PASSWORD="$(tr -d '\r\n' < "$REDIS_PASSWORD_FILE")"
  export REDIS_PASSWORD
fi
exec java \
  -XX:MaxRAMPercentage="${JVM_MAX_RAM_PERCENT:-35}" \
  -XX:MaxMetaspaceSize="${JVM_MAX_METASPACE:-256m}" \
  -XX:ReservedCodeCacheSize="${JVM_CODE_CACHE:-64m}" \
  -XX:MaxDirectMemorySize="${JVM_MAX_DIRECT:-32m}" \
  -Xss"${JVM_THREAD_STACK:-512k}" \
  -XX:ActiveProcessorCount="${JVM_ACTIVE_PROCESSORS:-1}" \
  ${JVM_GC:--XX:+UseSerialGC} \
  ${JVM_OOM_BEHAVIOUR:--XX:+ExitOnOutOfMemoryError} \
  -XX:+UseContainerSupport \
  -Djava.security.egd=file:/dev/./urandom \
  ${JAVA_OPTS:-} \
  -jar /app/app.jar
