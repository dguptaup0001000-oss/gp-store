#!/bin/sh
# Wrapper so operators can find the backup entrypoint under scripts/backup/.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec /bin/sh "$ROOT/backend/docker/backup/backup.sh" "$@"
