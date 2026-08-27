#!/usr/bin/env bash
# Decrypt an off-box .gpg dump with BACKUP_GPG_PASSPHRASE and restore it into
# an ISOLATED database. Never restores into production. Never prints the
# passphrase. Deletes plaintext on EXIT.
#
# Usage:
#   BACKUP_GPG_PASSPHRASE=... ./offbox-decrypt-restore.sh \
#     <file.dump.gpg> <host> <port> <user> <db> <password>
#
# Optional: RESULT_FILE=/path/to/result.txt (written for alerting)
# Optional: the matching .sha256 file next to the decrypted dump name.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DRILL="$ROOT/deploy/production/backup-restore-drill.sh"
GPG_FILE="${1:?encrypted dump (.gpg) required}"
HOST="${2:?host required}"
PORT="${3:?port required}"
USER="${4:?user required}"
DB="${5:?database required}"
PASSWORD="${6:?password required}"
RESULT_FILE="${RESULT_FILE:-}"

write_result() {
  local status="$1"
  local stage="$2"
  local detail="${3:-}"
  if [[ -n "$RESULT_FILE" ]]; then
    umask 077
    mkdir -p "$(dirname "$RESULT_FILE")"
    printf 'status=%s\nstage=%s\ndetail=%s\nfilename=%s\n' \
      "$status" "$stage" "$detail" "$(basename "$GPG_FILE")" > "$RESULT_FILE"
  fi
}

fail() {
  local stage="$1"
  local detail="$2"
  echo "ALERT=${stage} ${detail}" >&2
  write_result "$stage" "$stage" "$detail"
  exit 1
}

[ -f "$GPG_FILE" ] || fail MISSING "encrypted file not found"
[ -n "${BACKUP_GPG_PASSPHRASE:-}" ] || fail FAILED \
  "BACKUP_GPG_PASSPHRASE is not set. Add it as a GitHub Actions secret on the production environment."

case "$HOST" in
  *gpstore.co.in*|187.127.173.192)
    fail FAILED "refusing to restore against production host"
    ;;
esac
case "$DB" in
  gpstore|postgres|template0|template1)
    fail FAILED "refusing to restore into database '$DB'"
    ;;
esac

umask 077
plain_dir="$(mktemp -d)"
passfile="$(mktemp)"
trap 'rm -f "$passfile"; rm -rf "$plain_dir"' EXIT

base="$(basename "$GPG_FILE" .gpg)"
plain="$plain_dir/$base"
printf '%s' "$BACKUP_GPG_PASSPHRASE" > "$passfile"
chmod 600 "$passfile"

gpg --batch --yes --pinentry-mode loopback --decrypt \
  --passphrase-file "$passfile" \
  -o "$plain" "$GPG_FILE" \
  || fail FAILED "gpg decrypt failed"

rm -f "$passfile"
[ -s "$plain" ] || fail FAILED "decrypted dump is empty"

sha_sidecar="$(dirname "$GPG_FILE")/${base}.sha256"
if [[ -f "$sha_sidecar" ]]; then
  hash="$(awk '{print $1}' "$sha_sidecar")"
  printf '%s  %s\n' "$hash" "$base" > "$plain_dir/${base}.sha256"
  if ! (cd "$plain_dir" && sha256sum -c "${base}.sha256"); then
    fail FAILED "sha256 mismatch after decrypt"
  fi
else
  echo "No sidecar .sha256; recording sha256 of decrypted dump only."
fi

write_result SUCCESS decrypt "decrypted $(basename "$plain")"

# The GitHub runner does not install postgresql-client (apt has hung this
# repo before). Use the postgres:17 image as the client, same as schema-migrate.
docker run --rm --network=host \
  -e PGPASSWORD="$PASSWORD" \
  -v "$plain_dir:/backups" \
  -v "$DRILL:/drill.sh:ro" \
  postgres:17 \
  /bin/sh /drill.sh "/backups/$base" "$HOST" "$PORT" "$USER" "$DB" "$PASSWORD" \
  || fail RESTORE_FAILED "isolated restore failed"

tables_line="restore-ok"
write_result SUCCESS restore "$tables_line"
echo "OFFBOX_DECRYPT_RESTORE_OK file=$(basename "$GPG_FILE") db=$DB"
