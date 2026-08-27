#!/bin/sh
# Fixture test for backup.sh health mode. Does not talk to Postgres.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)"
SCRIPT="$ROOT/backend/docker/backup/backup.sh"
EVAL="$ROOT/deploy/production/evaluate-backup-status.sh"

dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
export BACKUP_DIR="$dir"
export BACKUP_HEALTH_MAX_MINUTES=1560

fail() { echo "FAIL: $*" >&2; exit 1; }

# missing
if /bin/sh "$SCRIPT" health; then
  fail "missing status.txt should be unhealthy"
fi

# failed attempt
printf 'taken_at=2026-08-27T01:00:00Z\nbytes=1\nsha256=abc\nfilename=gpstore-20260827T010000Z.dump\nstatus=FAILURE\ndetail=pg_dump failed\n' > "$dir/status.txt"
touch "$dir/gpstore-20260827T010000Z.dump"
printf 'gpstore-20260827T010000Z.dump\n' > "$dir/LATEST"
if /bin/sh "$SCRIPT" health; then
  fail "FAILURE status must be unhealthy even when a dump file exists"
fi
if bash "$EVAL" "$dir/status.txt"; then
  fail "evaluate-backup-status must fail on FAILURE"
fi
bash "$EVAL" "$dir/status.txt" >/tmp/eval-out 2>/dev/null || true
grep -q 'ALERT=FAILED' /tmp/eval-out || fail "expected ALERT=FAILED"

# successful and fresh
printf 'taken_at=%s\nbytes=1000\nsha256=abc\nfilename=gpstore-fresh.dump\nstatus=SUCCESS\ndetail=ok\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$dir/status.txt"
touch "$dir/gpstore-fresh.dump"
printf 'gpstore-fresh.dump\n' > "$dir/LATEST"
/bin/sh "$SCRIPT" health || fail "fresh SUCCESS should be healthy"
bash "$EVAL" "$dir/status.txt" | grep -q 'ALERT=HEALTHY' || fail "evaluate should report HEALTHY"

# stale: file mtime older than window
printf 'taken_at=2020-01-01T00:00:00Z\nbytes=1000\nsha256=abc\nfilename=gpstore-stale.dump\nstatus=SUCCESS\ndetail=ok\n' > "$dir/status.txt"
touch -d '30 hours ago' "$dir/gpstore-stale.dump"
printf 'gpstore-stale.dump\n' > "$dir/LATEST"
if /bin/sh "$SCRIPT" health; then
  fail "stale SUCCESS dump must be unhealthy"
fi
if MAX_AGE_HOURS=26 bash "$EVAL" "$dir/status.txt"; then
  fail "evaluate must fail on stale taken_at"
fi

# recovery: SUCCESS again
printf 'taken_at=%s\nbytes=1000\nsha256=abc\nfilename=gpstore-recovered.dump\nstatus=SUCCESS\ndetail=ok\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$dir/status.txt"
touch "$dir/gpstore-recovered.dump"
printf 'gpstore-recovered.dump\n' > "$dir/LATEST"
/bin/sh "$SCRIPT" health || fail "SUCCESS after FAILURE should recover to healthy"

# Off-box sync must refuse a local path (same VPS disk).
if BACKUP_OFFBOX_TARGET=/var/backups/gpstore bash "$ROOT/deploy/production/backup-offbox-sync.sh"; then
  fail "local path must not count as off-box"
fi
if BACKUP_OFFBOX_TARGET=user@localhost:/backups bash "$ROOT/deploy/production/backup-offbox-sync.sh"; then
  fail "localhost must not count as off-box"
fi

# Isolated restore must refuse production database names even with a dummy dump.
dummy="$(mktemp)"
printf 'not-a-real-dump\n' > "$dummy"
if bash "$ROOT/deploy/production/backup-restore-drill.sh" "$dummy" 127.0.0.1 5432 gpstore gpstore dummy-password; then
  fail "restore drill must refuse database name gpstore"
fi
if bash "$ROOT/deploy/production/backup-restore-drill.sh" "$dummy" api.gpstore.co.in 5432 gpstore gpstore_restore_probe dummy-password; then
  fail "restore drill must refuse production hostname"
fi
rm -f "$dummy"

# GPG AES256 roundtrip with an ephemeral passphrase (never committed).
if ! command -v gpg >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1; then
  fail "gpg and openssl are required for the off-box encrypt/decrypt self-test"
fi
plain_in="$(mktemp)"
plain_out="$(mktemp)"
printf 'gpstore-offbox-selftest\n' > "$plain_in"
bash "$ROOT/deploy/production/offbox-gpg-roundtrip.sh" "$plain_in" "$plain_out"
cmp "$plain_in" "$plain_out" || fail "gpg roundtrip changed bytes"
rm -f "$plain_in" "$plain_out"

# Off-box result file alerting.
eval_off="$ROOT/deploy/production/evaluate-offbox-result.sh"
res="$(mktemp)"
if bash "$eval_off" "$res"; then
  fail "missing offbox result must alert"
fi
printf 'status=UPLOAD_FAILED\nstage=upload\ndetail=artifact missing\n' > "$res"
if bash "$eval_off" "$res"; then
  fail "UPLOAD_FAILED must alert"
fi
bash "$eval_off" "$res" >/tmp/eval-offbox-out 2>/dev/null || true
grep -q 'ALERT=UPLOAD_FAILED' /tmp/eval-offbox-out || fail "expected ALERT=UPLOAD_FAILED"
printf 'status=RESTORE_FAILED\nstage=restore\ndetail=drill failed\n' > "$res"
if bash "$eval_off" "$res"; then
  fail "RESTORE_FAILED must alert"
fi
printf 'status=SUCCESS\nstage=restore\ndetail=ok\n' > "$res"
bash "$eval_off" "$res" | grep -q 'ALERT=HEALTHY' || fail "SUCCESS result should be HEALTHY"
rm -f "$res"

echo "backup health fixtures passed"
