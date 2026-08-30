#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/deploy/production/prune-backend-images.sh"
DEPLOY="$ROOT/deploy/production/deploy.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# newest first
printf '%s\n' "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "cccccccccccccccccccccccccccccccccccccccc" \
  "dddddddddddddddddddddddddddddddddddddddd" \
  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" \
  > "$WORKDIR/tags"

out="$(
  IMAGE_TAGS_FIXTURE="$WORKDIR/tags" \
  CURRENT_TAG="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  ROLLBACK_TAG="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  KEEP=3 \
  DRY_RUN=1 \
  SKIP_DANGLING_PRUNE=1 \
  bash "$SCRIPT"
)"

printf '%s\n' "$out" | grep -q 'keep gp-store-backend:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa (running or rollback)' \
  || fail "must keep current: $out"
printf '%s\n' "$out" | grep -q 'keep gp-store-backend:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb (running or rollback)' \
  || fail "must keep rollback: $out"
printf '%s\n' "$out" | grep -q 'keep gp-store-backend:cccccccccccccccccccccccccccccccccccccccc' \
  || fail "must keep one extra to reach KEEP=3: $out"
printf '%s\n' "$out" | grep -q 'rmi gp-store-backend:dddddddddddddddddddddddddddddddddddddddd' \
  || fail "must delete older tags: $out"
printf '%s\n' "$out" | grep -q 'rmi gp-store-backend:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' \
  || fail "must delete oldest: $out"

# Must not rmi current or rollback
printf '%s\n' "$out" | grep -q 'rmi gp-store-backend:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  && fail "must never rmi current"
printf '%s\n' "$out" | grep -q 'rmi gp-store-backend:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  && fail "must never rmi rollback"

grep -F 'disk-guard.sh' "$DEPLOY" || fail "deploy.sh must run disk-guard before build"
grep -F 'prune-backend-images.sh' "$DEPLOY" || fail "deploy.sh must prune only after success"
# prune must appear after DEPLOYMENT SUCCESS marker in the file
python3 - <<PY
from pathlib import Path
text = Path("$DEPLOY").read_text()
success = text.index("DEPLOYMENT SUCCESS")
prune = text.index("prune-backend-images.sh")
guard = text.index("disk-guard.sh")
if prune < success:
    raise SystemExit("prune must run after DEPLOYMENT SUCCESS")
if guard > text.index("Building image"):
    raise SystemExit("disk-guard must run before Building image")
print("deploy_order_ok")
PY

echo "test_prune_backend_images ok"
