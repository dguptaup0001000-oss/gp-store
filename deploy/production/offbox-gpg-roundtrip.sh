#!/usr/bin/env bash
# Encrypt a file with AES256, decrypt it, and prove the bytes match.
#
# Uses an ephemeral passphrase (never committed, never printed, deleted in
# the EXIT trap). This is the CI proof that BACKUP_GPG_PASSPHRASE-style
# encryption actually round-trips. Production uses the GitHub Actions secret
# of the same name; this script must not read that secret.
#
# Usage: offbox-gpg-roundtrip.sh <input> <output-decrypted> [output-gpg]
set -euo pipefail

IN="${1:?input file required}"
OUT="${2:?decrypted output path required}"
GPG_OUT="${3:-}"

[ -f "$IN" ] || { echo "input not found: $IN" >&2; exit 1; }

umask 077
passfile="$(mktemp)"
workdir="$(mktemp -d)"
trap 'rm -f "$passfile"; rm -rf "$workdir"' EXIT

# 32 random bytes, never echoed.
openssl rand -base64 32 > "$passfile"
chmod 600 "$passfile"

gpg_path="${GPG_OUT:-$workdir/$(basename "$IN").gpg}"
mkdir -p "$(dirname "$gpg_path")" "$(dirname "$OUT")"

gpg --batch --yes --pinentry-mode loopback --symmetric --cipher-algo AES256 \
  --passphrase-file "$passfile" \
  -o "$gpg_path" "$IN"

gpg --batch --yes --pinentry-mode loopback --decrypt \
  --passphrase-file "$passfile" \
  -o "$OUT" "$gpg_path"

orig="$(sha256sum "$IN" | awk '{print $1}')"
got="$(sha256sum "$OUT" | awk '{print $1}')"
if [[ "$orig" != "$got" ]]; then
  echo "ALERT=RESTORE_FAILED reason=gpg_roundtrip_checksum_mismatch" >&2
  exit 1
fi

echo "GPG_ROUNDTRIP_OK sha256=$got bytes=$(wc -c < "$OUT" | tr -d ' ')"
