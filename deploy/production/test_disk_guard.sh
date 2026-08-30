#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=deploy/production/disk-guard.sh
source "$ROOT/deploy/production/disk-guard.sh"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

printf '%s\n' "Filesystem 1024-blocks Used Available Use% Mounted" "overlay 100 90 500 95% /" \
  > "$WORKDIR/low.df"
printf '%s\n' "Filesystem 1024-blocks Used Available Use% Mounted" "overlay 100 10 2000000 1% /" \
  > "$WORKDIR/ok.df"

DF_FIXTURE="$WORKDIR/low.df"
if require_free_disk / 1048576; then
  fail "low disk must fail"
fi

DF_FIXTURE="$WORKDIR/ok.df"
require_free_disk / 1048576 || fail "enough disk must pass"

echo "test_disk_guard ok"
