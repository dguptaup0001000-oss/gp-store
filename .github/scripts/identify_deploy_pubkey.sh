#!/usr/bin/env bash
# Derive the GitHub Actions deploy *public* key and fingerprint from
# PROD_SSH_PRIVATE_KEY. Never prints the private key.
#
# Usage:
#   PROD_SSH_PRIVATE_KEY='-----BEGIN …' ./identify_deploy_pubkey.sh
#   IDENTIFY_DEPLOY_PUBKEY_SELFTEST=1 ./identify_deploy_pubkey.sh
set -Eeuo pipefail

selftest() {
  local dir key
  dir="$(mktemp -d)"
  trap 'rm -rf "$dir"' RETURN
  ssh-keygen -t ed25519 -f "$dir/k" -N "" -C "gpstore-identify-selftest" -q
  local expected
  expected="$(ssh-keygen -lf "$dir/k.pub" | awk '{print $2}')"
  local got
  got="$(IDENTIFY_DEPLOY_PUBKEY_SELFTEST=0 PROD_SSH_PRIVATE_KEY="$(cat "$dir/k")" "$0" | awk -F= '/^fingerprint=/{print $2}')"
  if [ "$got" != "$expected" ]; then
    echo "selftest failed: fingerprint mismatch" >&2
    exit 1
  fi
  echo "identify_deploy_pubkey selftest ok"
}

if [ "${IDENTIFY_DEPLOY_PUBKEY_SELFTEST:-}" = "1" ]; then
  selftest
  exit 0
fi

if [ -z "${PROD_SSH_PRIVATE_KEY:-}" ]; then
  echo "PROD_SSH_PRIVATE_KEY is empty; cannot derive a public key." >&2
  exit 1
fi

umask 077
KEY="$(mktemp)"
PUBFILE="$(mktemp)"
ERRFILE="$(mktemp)"
trap 'rm -f "$KEY" "$PUBFILE" "$ERRFILE"' EXIT
# The secret may be stored without a trailing newline. ssh-keygen needs one.
printf '%s\n' "$PROD_SSH_PRIVATE_KEY" > "$KEY"
chmod 600 "$KEY"

if ! ssh-keygen -y -f "$KEY" >"$PUBFILE" 2>"$ERRFILE"; then
  echo "Could not derive a public key from PROD_SSH_PRIVATE_KEY." >&2
  echo "The secret is present but is not a usable OpenSSH/PEM private key." >&2
  echo "(Error text omitted so a malformed key is not echoed.)" >&2
  exit 1
fi
chmod 600 "$PUBFILE"

PUB="$(cat "$PUBFILE")"
FP="$(ssh-keygen -lf "$PUBFILE" | awk '{print $2}')"
BITS="$(ssh-keygen -lf "$PUBFILE" | awk '{print $1}')"
TYPE="$(ssh-keygen -lf "$PUBFILE" | awk '{print $4}' | tr -d '()')"

echo "deploy_key_bits=$BITS"
echo "deploy_key_type=${TYPE:-unknown}"
echo "fingerprint=$FP"
echo "public_key=$PUB"
echo
echo "Install that public_key as ONE line in the VPS file:"
echo "  ~PROD_USER/.ssh/authorized_keys   (mode 600, directory ~/.ssh mode 700)"
echo "If PROD_USER is root: /root/.ssh/authorized_keys"
echo "Do not paste the private key onto the VPS or into chat."
echo "Compare fingerprint with: ssh-keygen -lf ~/.ssh/authorized_keys"
