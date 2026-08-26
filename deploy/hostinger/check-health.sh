#!/usr/bin/env bash
# Legacy name. Production is Traefik + Compose, not host Nginx + published 8081.
# Redis is not on localhost.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
echo "Use deploy/production/check-health.sh (Traefik + Compose exec)." >&2
exec "$ROOT/deploy/production/check-health.sh" "$@"
