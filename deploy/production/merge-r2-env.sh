#!/usr/bin/env bash
# Copy R2_* keys from a source env file into backend/.env without printing
# values. Default source is the operator file that already holds production
# R2 credentials. Default dest is the file Docker Compose interpolates.
#
# Usage:
#   ./deploy/production/merge-r2-env.sh
#   ./deploy/production/merge-r2-env.sh /opt/gpstore/env-production /opt/gp-store/backend/.env
#   MERGE_R2_ENV_SELFTEST=1 ./deploy/production/merge-r2-env.sh
#
# Never overwrites a dest value that is already set unless MERGE_R2_FORCE=1.
# Never prints secret values. Prints only key names and set/blank/copied.
# Values are never passed on a subprocess command line.
set -Eeuo pipefail

KEYS=(
  R2_ACCOUNT_ID
  R2_ENDPOINT
  R2_ACCESS_KEY_ID
  R2_SECRET_ACCESS_KEY
  R2_BUCKET_NAME
  R2_PUBLIC_BASE_URL
)

read_key() {
  local file="$1" key="$2" line
  if [ ! -f "$file" ]; then
    return 0
  fi
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  if [ -z "$line" ]; then
    return 0
  fi
  printf '%s' "${line#*=}"
}

is_blank() {
  [ -z "${1:-}" ]
}

write_key() {
  # $1 file  $2 key  $3 value — rewrite last assignment or append.
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp)"
  local replaced=0
  if [ -s "$file" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      if [[ "$line" == "${key}="* ]]; then
        printf '%s=%s\n' "$key" "$value"
        replaced=1
      else
        printf '%s\n' "$line"
      fi
    done < "$file" > "$tmp"
  fi
  if [ "$replaced" -eq 0 ]; then
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$file"
}

selftest() {
  local dir src dest
  dir="$(mktemp -d)"
  trap 'rm -rf "$dir"' RETURN
  src="$dir/src"
  dest="$dir/dest"
  printf '%s\n' \
    "R2_ACCOUNT_ID=acct-test" \
    "R2_ENDPOINT=https://example.invalid.r2.cloudflarestorage.com" \
    "R2_ACCESS_KEY_ID=key-test" \
    "R2_SECRET_ACCESS_KEY=secret-test" \
    "R2_BUCKET_NAME=gp-store-images" \
    "R2_PUBLIC_BASE_URL=" \
    > "$src"
  printf '%s\n' "JWT_SECRET=keep-me" "R2_BUCKET_NAME=already-set" > "$dest"
  local out
  out="$dir/out"
  MERGE_R2_ENV_SELFTEST=0 "$0" "$src" "$dest" > "$out"
  grep -q 'R2_ACCOUNT_ID=copied' "$out"
  grep -q 'R2_BUCKET_NAME=kept' "$out"
  grep -q 'R2_PUBLIC_BASE_URL=blank' "$out"
  grep -q 'JWT_SECRET=keep-me' "$dest"
  grep -q 'R2_ACCOUNT_ID=acct-test' "$dest"
  grep -q 'R2_BUCKET_NAME=already-set' "$dest"
  if grep -q 'secret-test' "$out"; then
    echo "selftest failed: secret value leaked to stdout" >&2
    exit 1
  fi
  echo "merge-r2-env selftest ok"
}

if [ "${MERGE_R2_ENV_SELFTEST:-}" = "1" ]; then
  selftest
  exit 0
fi

SRC="${1:-/opt/gpstore/env-production}"
DEST="${2:-/opt/gp-store/backend/.env}"

if [ ! -f "$SRC" ]; then
  echo "source missing: $SRC" >&2
  exit 1
fi
if [ ! -f "$DEST" ]; then
  echo "dest missing: $DEST (refusing to create a new env file)" >&2
  exit 1
fi

umask 077
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
cp "$DEST" "$TMP"

copied=0
kept=0
blank=0
for key in "${KEYS[@]}"; do
  src_val="$(read_key "$SRC" "$key")"
  dest_val="$(read_key "$TMP" "$key")"
  if ! is_blank "$dest_val" && [ "${MERGE_R2_FORCE:-0}" != "1" ]; then
    echo "$key=kept"
    kept=$((kept + 1))
    continue
  fi
  write_key "$TMP" "$key" "$src_val"
  if is_blank "$src_val"; then
    echo "$key=blank"
    blank=$((blank + 1))
  else
    echo "$key=copied"
    copied=$((copied + 1))
  fi
done

public_val="$(read_key "$TMP" R2_PUBLIC_BASE_URL)"
if ! is_blank "$public_val"; then
  echo "WARNING: R2_PUBLIC_BASE_URL is set. Private-bucket mode wants it empty."
fi

chmod 600 "$TMP"
cp "$TMP" "$DEST"
chmod 600 "$DEST"
echo "dest=$DEST mode=$(stat -c '%a' "$DEST") copied=$copied kept=$kept blank=$blank"
echo "Next: from /opt/gp-store/backend run  docker compose up -d --no-deps --no-build backend"
echo "Do not run docker compose down -v."
