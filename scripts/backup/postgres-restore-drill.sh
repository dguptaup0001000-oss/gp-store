#!/bin/sh
# Wrapper so operators can find the restore drill under scripts/backup/.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec /bin/sh "$ROOT/deploy/production/backup-restore-drill.sh" "$@"
