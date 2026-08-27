#!/usr/bin/env bash
# Pull the latest production dump OFF the VPS onto this machine (CI runner).
# Does not restore into production. Does not print secrets or dump contents.
#
# Required env: PROD_HOST PROD_USER PROD_SSH_PRIVATE_KEY
# Optional: PROD_PORT PROD_APP_DIR BACKUP_GPG_PASSPHRASE
#
# Writes into $OUT_DIR (default ./offbox-out):
#   dump file, .sha256, status.txt, metadata.json
#   and .gpg if BACKUP_GPG_PASSPHRASE is set.
set -euo pipefail

OUT_DIR="${OUT_DIR:-./offbox-out}"
PROD_PORT="${PROD_PORT:-22}"
PROD_APP_DIR="${PROD_APP_DIR:-/opt/gp-store}"
COMPOSE_FILE="${PROD_APP_DIR}/backend/docker-compose.yml"

missing=0
for v in PROD_HOST PROD_USER PROD_SSH_PRIVATE_KEY; do
  if [[ -z "${!v:-}" ]]; then
    echo "$v is not set" >&2
    missing=1
  fi
done
if [[ "$missing" -ne 0 ]]; then
  exit 1
fi

umask 077
KEY="$(mktemp)"
trap 'rm -f "$KEY"' EXIT
printf '%s\n' "$PROD_SSH_PRIVATE_KEY" > "$KEY"
chmod 600 "$KEY"

ssh_vps() {
  ssh -i "$KEY" \
    -p "$PROD_PORT" \
    -o IdentitiesOnly=yes \
    -o BatchMode=yes \
    -o StrictHostKeyChecking=accept-new \
    "${PROD_USER}@${PROD_HOST}" \
    "$@"
}

mkdir -p "$OUT_DIR"

echo "Taking a fresh on-VPS dump (sidecar once; does not restore)..."
ssh_vps "cd '$PROD_APP_DIR/backend' && docker compose -f docker-compose.yml exec -T backup /bin/sh /backup.sh once"

LATEST="$(ssh_vps "cd '$PROD_APP_DIR/backend' && docker compose -f docker-compose.yml exec -T backup cat /backups/LATEST" | tr -d '\r')"
if [[ -z "$LATEST" ]]; then
  echo "LATEST is empty after dump" >&2
  exit 1
fi
echo "Pulling $LATEST off the VPS..."

ssh_vps "cd '$PROD_APP_DIR/backend' && docker compose -f docker-compose.yml exec -T backup cat /backups/$LATEST" \
  > "$OUT_DIR/$LATEST"
ssh_vps "cd '$PROD_APP_DIR/backend' && docker compose -f docker-compose.yml exec -T backup cat /backups/${LATEST}.sha256" \
  > "$OUT_DIR/${LATEST}.sha256"
ssh_vps "cd '$PROD_APP_DIR/backend' && docker compose -f docker-compose.yml exec -T backup cat /backups/status.txt" \
  > "$OUT_DIR/status.txt"

(cd "$OUT_DIR" && sha256sum -c "${LATEST}.sha256")

bytes="$(wc -c < "$OUT_DIR/$LATEST" | tr -d ' ')"
if [[ "${bytes:-0}" -lt 100 ]]; then
  echo "Pulled dump is too small ($bytes bytes)" >&2
  exit 1
fi

# Integrity without restoring into production Postgres.
docker run --rm -v "$(cd "$OUT_DIR" && pwd):/backups:ro" postgres:17 \
  pg_restore --list "/backups/$LATEST" >/dev/null

status="$(grep '^status=' "$OUT_DIR/status.txt" | tail -n 1 | cut -d= -f2- | tr -d '\r')"
if [[ "$status" != "SUCCESS" ]]; then
  echo "status.txt is $status, not SUCCESS" >&2
  exit 1
fi

sha="$(awk '{print $1}' "$OUT_DIR/${LATEST}.sha256")"
taken_at="$(grep '^taken_at=' "$OUT_DIR/status.txt" | tail -n 1 | cut -d= -f2- | tr -d '\r')"

cat > "$OUT_DIR/metadata.json" <<EOF
{"filename":"$LATEST","bytes":$bytes,"sha256":"$sha","taken_at":"$taken_at","status":"$status","offbox":true}
EOF

if [[ -n "${BACKUP_GPG_PASSPHRASE:-}" ]]; then
  passfile="$(mktemp)"
  printf '%s' "$BACKUP_GPG_PASSPHRASE" > "$passfile"
  chmod 600 "$passfile"
  gpg --batch --yes --symmetric --cipher-algo AES256 \
    --passphrase-file "$passfile" \
    -o "$OUT_DIR/${LATEST}.gpg" "$OUT_DIR/$LATEST"
  rm -f "$passfile"
  rm -f "$OUT_DIR/$LATEST"
  echo "Encrypted dump retained as ${LATEST}.gpg; plaintext removed from runner."
else
  echo "BACKUP_GPG_PASSPHRASE is unset. Plaintext dump stays on the runner only for this job; do not upload it as an artifact."
fi

echo "OFFBOX_PULL_OK $LATEST bytes=$bytes sha256=$sha"
