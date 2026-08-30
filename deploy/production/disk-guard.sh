#!/usr/bin/env bash
# Fail-fast disk preflight for production deploy. Sourced or executed.
# Overrides for tests: DF_CMD, DISK_MIN_FREE_KB
set -Eeuo pipefail

DISK_PATH="${DISK_PATH:-/var/lib/docker}"
DISK_MIN_FREE_KB="${DISK_MIN_FREE_KB:-1048576}" # 1 GiB
DF_CMD="${DF_CMD:-df}"

disk_avail_kb() {
  local path="$1"
  if [ -n "${DF_FIXTURE:-}" ]; then
    awk 'NR==2 {print $4}' "$DF_FIXTURE"
    return
  fi
  "$DF_CMD" -Pk "$path" | awk 'NR==2 {print $4}'
}

require_free_disk() {
  local path="${1:-$DISK_PATH}"
  local min_kb="${2:-$DISK_MIN_FREE_KB}"
  local avail
  if [ ! -d "$path" ]; then
    path="/"
  fi
  avail="$(disk_avail_kb "$path")"
  if ! [[ "$avail" =~ ^[0-9]+$ ]]; then
    echo "disk-guard: could not read free space for $path" >&2
    return 1
  fi
  if [ "$avail" -lt "$min_kb" ]; then
    echo "disk-guard: ${avail} KiB free on $path; need ${min_kb} KiB" >&2
    return 1
  fi
  echo "disk-guard: ${avail} KiB free on $path (min ${min_kb} KiB)"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  require_free_disk "$@"
fi
