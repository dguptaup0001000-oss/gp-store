#!/usr/bin/env bash
# Is the shop actually up, from outside?
#
# WHY FROM OUTSIDE AND NOT OVER SSH. The deploy already checks health on the
# box. This checks what a customer gets: DNS, the certificate, Traefik, and
# the app behind it. A backend that is perfectly healthy inside a container
# while the certificate expired is exactly the outage nobody notices until a
# customer phones.
#
# WHY IT RETRIES. A single failed request is a blip, not an outage, and an
# alarm that cries wolf gets muted - at which point the shop has no alarm at
# all. Only a run of consecutive failures counts.
#
# Prints no secret: the health endpoint is public and needs no credential,
# which is the other reason this probe is the public one.
set -Eeuo pipefail

URL="${PUBLIC_HEALTH_URL:-https://api.gpstore.co.in/v1/api/health/ready}"
ATTEMPTS="${HEALTH_ATTEMPTS:-3}"
GAP_SECONDS="${HEALTH_RETRY_GAP_SECONDS:-20}"
TIMEOUT="${HEALTH_TIMEOUT_SECONDS:-10}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

last_status=""
last_body=""

for attempt in $(seq 1 "$ATTEMPTS"); do
  set +e
  last_body="$(curl -sS --max-time "$TIMEOUT" -o /tmp/health.$$ -w '%{http_code}' "$URL" 2>/tmp/health.err.$$)"
  rc=$?
  set -e
  last_status="$last_body"

  if [ "$rc" -eq 0 ] && [ "$last_status" = "200" ]; then
    log "OK: $URL answered 200 on attempt $attempt"
    cat "/tmp/health.$$" 2>/dev/null || true
    echo
    rm -f "/tmp/health.$$" "/tmp/health.err.$$"
    exit 0
  fi

  if [ "$rc" -ne 0 ]; then
    log "attempt $attempt/$ATTEMPTS: could not reach $URL ($(head -c 200 "/tmp/health.err.$$" 2>/dev/null))"
  else
    log "attempt $attempt/$ATTEMPTS: $URL answered HTTP $last_status"
  fi

  # No sleep after the final attempt - it would only delay the alarm.
  if [ "$attempt" -lt "$ATTEMPTS" ]; then
    sleep "$GAP_SECONDS"
  fi
done

log "DOWN: $URL failed $ATTEMPTS consecutive checks."
log "Last response body (first 500 bytes):"
head -c 500 "/tmp/health.$$" 2>/dev/null || true
echo
rm -f "/tmp/health.$$" "/tmp/health.err.$$"

# Non-zero fails the workflow, which is what emails whoever watches the repo.
exit 1
