#!/usr/bin/env bash
# Bring the shop back on a replacement server, from the encrypted off-box
# backup, in one command.
#
# WHY THIS EXISTS WHEN offbox-decrypt-restore.sh ALREADY DOES MOST OF IT.
# That script deliberately refuses to touch production - it is the drill, and
# it is right to refuse. The consequence was that the ONE path nobody
# rehearses, restoring into the real database on a new box, had no script at
# all: eight manual steps out of DISASTER_RECOVERY.md, performed once, at
# whatever hour the server died, by someone who has never done it before.
# That is where hours go, and where a tired operator restores yesterday's
# dump over today's data.
#
# So this is the emergency path, made safe by construction rather than by
# being left undone:
#
#   * It cannot run by accident. I_HAVE_LOST_THE_PRODUCTION_SERVER=yes is not
#     a flag anybody types without meaning it.
#   * It refuses a database that still has orders in it, unless the operator
#     overrides that too, because "the box is fine, I just wanted a copy" is
#     the mistake that turns an incident into a catastrophe.
#   * It checks everything it needs BEFORE it changes anything, so a missing
#     passphrase or a truncated download fails while the database is still
#     untouched.
#   * It verifies afterwards and says what it found, rather than exiting 0
#     and leaving the operator to guess.
#
# WHAT IT WILL NOT DO, because it cannot be done honestly from a script:
# provision the server, recreate backend/.env from the operator's own
# secrets, or move DNS. Those need a person with credentials this script must
# never hold. It checks that the first two have been done and tells the
# operator about the third.
#
# NEVER PRINTS: the GPG passphrase, the database password, or any value out
# of .env. It reports which keys are present, never what they contain.
#
# Usage:
#   I_HAVE_LOST_THE_PRODUCTION_SERVER=yes \
#   BACKUP_GPG_PASSPHRASE_FILE=/safe/passphrase \
#     ./recover-onto-new-vps.sh gpstore-20260902T131500Z.dump.gpg \
#        127.0.0.1 5432 gpstore gpstore
#
#   RECOVER_SELFTEST=1 ./recover-onto-new-vps.sh   # proves the guards, no DB
set -euo pipefail

GPG_FILE="${1:-}"
HOST="${2:-}"
PORT="${3:-}"
DB_USER="${4:-}"
DB_NAME="${5:-}"

log()  { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
step() { printf '\n=== %s\n' "$*"; }
die()  { printf 'STOPPED: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- the guards
#
# Split out so the self-test can exercise the decisions without a database.

check_operator_meant_it() {
  if [[ "${I_HAVE_LOST_THE_PRODUCTION_SERVER:-}" != "yes" ]]; then
    die "this restores over a production database.
  If the server is genuinely gone, re-run with:
    I_HAVE_LOST_THE_PRODUCTION_SERVER=yes
  If you only want to CHECK a backup restores, use the drill instead - it
  cannot touch production:
    deploy/production/offbox-decrypt-restore.sh"
  fi
}

# The target must look like a fresh box. A database with orders in it is a
# LIVE shop, and restoring a six-hour-old dump over it destroys every order
# taken since. That mistake is unrecoverable, so it takes a second explicit
# acknowledgement rather than a prompt somebody can hold enter through.
check_target_is_empty() {
  local existing_orders="$1"
  if [[ "$existing_orders" == "0" ]]; then
    return 0
  fi
  if [[ "${OVERWRITE_A_DATABASE_WITH_ORDERS_IN_IT:-}" == "yes" ]]; then
    log "WARNING: target holds $existing_orders order(s) and will be overwritten, as instructed."
    return 0
  fi
  die "the target database already holds $existing_orders order(s).
  That is a live shop, not a replacement server. Restoring here would destroy
  every order taken since the backup was made.
  If you are certain this is the right thing, re-run with:
    OVERWRITE_A_DATABASE_WITH_ORDERS_IN_IT=yes"
}

# .env carries the shop's secrets and this script must never read their
# values - only confirm the operator has already put them back, because a
# backend that boots without them fails in ways that look like a bad restore.
check_env_has_keys() {
  local env_file="$1"
  shift
  local missing=()
  local key
  for key in "$@"; do
    if ! grep -qE "^${key}=.+" "$env_file" 2>/dev/null; then
      missing+=("$key")
    fi
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    die "backend/.env is missing: ${missing[*]}
  Recreate it from your own records before restoring. This script cannot
  invent them, and a backend that starts without them will look like a
  failed restore when it is not."
  fi
  log "backend/.env carries all required keys (values not read)."
}

# ---------------------------------------------------------------- self-test
if [[ "${RECOVER_SELFTEST:-0}" == "1" ]]; then
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  failures=0
  expect() {
    local name="$1" want="$2"; shift 2
    local code=0
    ( "$@" ) >/dev/null 2>&1 || code=$?
    if [[ "$code" != "$want" ]]; then
      echo "FAIL $name: exit=$code want=$want"; failures=$((failures+1))
    else
      echo "ok   $name"
    fi
  }

  expect "refuses without the acknowledgement" 1 check_operator_meant_it
  I_HAVE_LOST_THE_PRODUCTION_SERVER=yes \
    expect "proceeds with the acknowledgement" 0 check_operator_meant_it
  I_HAVE_LOST_THE_PRODUCTION_SERVER=y \
    expect "a near-miss acknowledgement is not enough" 1 check_operator_meant_it

  expect "an empty target is fine" 0 check_target_is_empty 0
  expect "a target with orders is refused" 1 check_target_is_empty 42
  OVERWRITE_A_DATABASE_WITH_ORDERS_IN_IT=yes \
    expect "and can be overridden deliberately" 0 check_target_is_empty 42

  printf 'DB_PASSWORD=secret-not-read\nJWT_SECRET=also-not-read\n' > "$tmp/.env"
  expect "an env with the keys passes" 0 check_env_has_keys "$tmp/.env" DB_PASSWORD JWT_SECRET
  expect "a missing key is refused" 1 check_env_has_keys "$tmp/.env" DB_PASSWORD CASHFREE_SECRET_KEY
  printf 'DB_PASSWORD=\n' > "$tmp/empty-env"
  expect "an empty value counts as missing" 1 check_env_has_keys "$tmp/empty-env" DB_PASSWORD

  # The guards must not echo what they read. A restore script that prints
  # .env into a terminal history is its own incident.
  out="$(check_env_has_keys "$tmp/.env" DB_PASSWORD JWT_SECRET 2>&1 || true)"
  if [[ "$out" == *"secret-not-read"* || "$out" == *"also-not-read"* ]]; then
    echo "FAIL guards must never print a secret's value"; failures=$((failures+1))
  else
    echo "ok   guards report which keys exist, never their values"
  fi

  [[ "$failures" -eq 0 ]] || { echo "recovery self-test: $failures failure(s)"; exit 1; }
  echo "recovery self-test: all checks passed"
  exit 0
fi

# ---------------------------------------------------------------- the real run
[[ -n "$GPG_FILE" && -n "$HOST" && -n "$PORT" && -n "$DB_USER" && -n "$DB_NAME" ]] \
  || die "usage: recover-onto-new-vps.sh <file.dump.gpg> <host> <port> <user> <db>"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

step "1/6  Is this really what you meant?"
check_operator_meant_it
log "Acknowledged. Recovering $DB_NAME on $HOST:$PORT from $(basename "$GPG_FILE")."

step "2/6  Everything this needs, checked before anything changes"
[[ -f "$GPG_FILE" ]] || die "encrypted backup not found: $GPG_FILE"
command -v gpg  >/dev/null || die "gpg is not installed on this box."
command -v psql >/dev/null || die "psql is not installed on this box."
command -v pg_restore >/dev/null || die "pg_restore is not installed on this box."

PASSPHRASE_FILE="${BACKUP_GPG_PASSPHRASE_FILE:-}"
if [[ -z "$PASSPHRASE_FILE" ]]; then
  die "set BACKUP_GPG_PASSPHRASE_FILE to a file holding the passphrase.
  A file, not an environment variable: an exported passphrase shows up in
  the process list and in shell history."
fi
[[ -r "$PASSPHRASE_FILE" ]] || die "cannot read the passphrase file: $PASSPHRASE_FILE"
[[ -s "$PASSPHRASE_FILE" ]] || die "the passphrase file is empty: $PASSPHRASE_FILE"

ENV_FILE="${BACKEND_ENV_FILE:-$ROOT/backend/.env}"
[[ -f "$ENV_FILE" ]] || die "backend/.env not found at $ENV_FILE.
  Recreate it from your own records first - the restore is useless without it."
check_env_has_keys "$ENV_FILE" DB_NAME DB_USERNAME DB_PASSWORD JWT_SECRET

PGPASSWORD="$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -n1 | cut -d= -f2-)"
export PGPASSWORD
psql -h "$HOST" -p "$PORT" -U "$DB_USER" -d postgres -Atc 'SELECT 1' >/dev/null \
  || die "cannot reach Postgres at $HOST:$PORT as $DB_USER.
  Start Postgres (and only Postgres) before restoring - the backend must stay
  down until the data is back."
log "Postgres is reachable. gpg, psql and pg_restore are present."

step "3/6  Is the target a replacement, or a live shop?"
EXISTING_ORDERS="$(psql -h "$HOST" -p "$PORT" -U "$DB_USER" -d "$DB_NAME" -Atc \
  "SELECT COUNT(*) FROM orders" 2>/dev/null || echo 0)"
log "Target $DB_NAME currently holds $EXISTING_ORDERS order(s)."
check_target_is_empty "$EXISTING_ORDERS"

step "4/6  Decrypt, and prove the file arrived whole"
WORK="$(mktemp -d)"
# umask before anything is written: the plaintext dump is the entire shop.
umask 077
cleanup() {
  # Shred rather than unlink where possible - a plaintext dump left on a
  # recovered box is a copy of every customer record.
  if [[ -d "$WORK" ]]; then
    find "$WORK" -type f -exec shred -u {} + 2>/dev/null || true
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT
PLAIN="$WORK/$(basename "${GPG_FILE%.gpg}")"

gpg --batch --yes --quiet --decrypt --pinentry-mode loopback \
    --passphrase-file "$PASSPHRASE_FILE" \
    -o "$PLAIN" "$GPG_FILE" \
  || die "decryption failed. Wrong passphrase, or a truncated download.
  Nothing has been changed in the database."
log "Decrypted $(basename "$PLAIN") ($(wc -c < "$PLAIN") bytes)."

SUM_FILE="${GPG_FILE%.gpg}.sha256"
if [[ -f "$SUM_FILE" ]]; then
  ( cd "$WORK" && sha256sum -c "$SUM_FILE" >/dev/null ) \
    || die "checksum mismatch - the backup is corrupt or incomplete.
  Do not restore it. Fetch the artifact again, or use the previous one."
  log "Checksum verified against $(basename "$SUM_FILE")."
else
  # Loud, because an unverified restore is a guess about the shop's data.
  log "WARNING: no .sha256 beside the backup, so integrity is UNVERIFIED."
fi

step "5/6  Restore"
# --clean --if-exists so a half-restored earlier attempt does not leave two
# generations of schema fighting. Not --create: the database already exists
# and its ownership belongs to the deploy, not to this dump.
pg_restore --no-owner --no-privileges --clean --if-exists \
  -h "$HOST" -p "$PORT" -U "$DB_USER" -d "$DB_NAME" "$PLAIN" \
  || die "pg_restore failed. The database is in an unknown state - do NOT
  start the backend against it. Re-run this script once the cause is fixed."
log "Restored into $DB_NAME."

step "6/6  Does the shop's data look like a shop?"
# A restore that exits 0 having created an empty schema is the failure worth
# catching: it looks like success everywhere except in the data.
read -r TABLES CUSTOMERS ORDERS PAYMENTS FLYWAY <<EOF
$(psql -h "$HOST" -p "$PORT" -U "$DB_USER" -d "$DB_NAME" -Atc \
  "SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'),
          (SELECT COUNT(*) FROM customers),
          (SELECT COUNT(*) FROM orders),
          (SELECT COUNT(*) FROM payments),
          (SELECT COALESCE(MAX(version::numeric)::text,'none') FROM flyway_schema_history)" \
  | tr '|' ' ')
EOF

log "tables=$TABLES customers=$CUSTOMERS orders=$ORDERS payments=$PAYMENTS schema_version=$FLYWAY"

[[ "${TABLES:-0}" -gt 10 ]] || die "only ${TABLES:-0} tables restored - this is not a full database."
[[ "$FLYWAY" != "none" ]] || die "no Flyway history restored - the schema is not the shop's."

cat <<NEXT

RESTORED. The database is back. The shop is not up yet - three things are
left, and each one needs a person:

  1. Start the backend:
       cd $ROOT/backend && docker compose up -d backend
  2. Confirm it is actually serving:
       deploy/production/check-health.sh
     and that admin routes still refuse an anonymous caller (401).
  3. Point DNS at this server if its address changed, then wait for TLS.
     Until DNS moves, customers still reach the old address.

Then run deploy/production/probe-public-health.sh from anywhere to confirm a
customer can reach the shop, not just this box.
NEXT
