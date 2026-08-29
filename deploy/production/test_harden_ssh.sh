#!/usr/bin/env bash
# Tests for harden-ssh.sh. Does not touch the real /etc/ssh.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/deploy/production/harden-ssh.sh"
PREPARE="$ROOT/deploy/production/prepare-vps.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -x "$SCRIPT" ] || chmod +x "$SCRIPT"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

export SSHD_CONFIG="$WORKDIR/sshd_config"
export SSHD_DROPIN="$WORKDIR/sshd_config.d/99-gpstore.conf"
export FAIL2BAN_JAIL="$WORKDIR/fail2ban/jail.d/gpstore-sshd.conf"
export SKIP_APT=1
export SKIP_SERVICE_RESTART=1

printf '%s\n' "PasswordAuthentication yes" "PermitRootLogin yes" > "$SSHD_CONFIG"
bash "$SCRIPT"

grep -qx 'PasswordAuthentication no' "$SSHD_DROPIN" \
  || fail "drop-in must set PasswordAuthentication no"
grep -qx 'PermitRootLogin prohibit-password' "$SSHD_DROPIN" \
  || fail "yes -> prohibit-password"
grep -qx 'enabled = true' "$FAIL2BAN_JAIL" || fail "fail2ban sshd jail must be enabled"
grep -qx '\[sshd\]' "$FAIL2BAN_JAIL" || fail "fail2ban jail must be [sshd]"

if grep -nEi 'ufw[[:space:]]+allow[[:space:]]+from|AllowUsers|AllowGroups' \
    "$SCRIPT" "$FAIL2BAN_JAIL" "$SSHD_DROPIN"; then
  fail "must not IP-allowlist SSH (GitHub Actions egress rotates)"
fi

printf '%s\n' "PasswordAuthentication yes" "PermitRootLogin no" > "$SSHD_CONFIG"
rm -f "$SSHD_DROPIN"
bash "$SCRIPT"
grep -qx 'PermitRootLogin no' "$SSHD_DROPIN" \
  || fail "existing PermitRootLogin no must be preserved"

grep -F 'harden-ssh.sh' "$PREPARE" \
  || fail "prepare-vps.sh must invoke harden-ssh.sh"

echo "test_harden_ssh ok"
