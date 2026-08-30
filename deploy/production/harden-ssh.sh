#!/usr/bin/env bash
# Idempotent sshd + fail2ban hardening for the Hostinger VPS.
#
# Key-only auth. Do not IP-allowlist port 22: GitHub Actions egress rotates
# and would break Deploy Production.
#
# Overrides for tests (do not set these on the VPS):
#   SSHD_CONFIG, SSHD_DROPIN, FAIL2BAN_JAIL, SKIP_APT, SKIP_SERVICE_RESTART
set -Eeuo pipefail

SSHD_CONFIG="${SSHD_CONFIG:-/etc/ssh/sshd_config}"
SSHD_DROPIN="${SSHD_DROPIN:-/etc/ssh/sshd_config.d/99-gpstore.conf}"
FAIL2BAN_JAIL="${FAIL2BAN_JAIL:-/etc/fail2ban/jail.d/gpstore-sshd.conf}"
SKIP_APT="${SKIP_APT:-0}"
SKIP_SERVICE_RESTART="${SKIP_SERVICE_RESTART:-0}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

permit_root_login() {
  local current=""
  if [ -f "$SSHD_CONFIG" ]; then
    current="$(awk 'tolower($1)=="permitrootlogin" {print tolower($2)}' "$SSHD_CONFIG" | tail -n1 || true)"
  fi
  if [ -n "$current" ] && [ "$current" = "no" ]; then
    printf '%s\n' "no"
    return
  fi
  printf '%s\n' "prohibit-password"
}

write_sshd_dropin() {
  local dest_dir root_login
  dest_dir="$(dirname "$SSHD_DROPIN")"
  mkdir -p "$dest_dir"
  root_login="$(permit_root_login)"
  cat > "$SSHD_DROPIN" <<EOF
# Managed by deploy/production/harden-ssh.sh. Key-only.
PasswordAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
PubkeyAuthentication yes
PermitRootLogin ${root_login}
EOF
  chmod 644 "$SSHD_DROPIN"
  log "Wrote $SSHD_DROPIN (PermitRootLogin ${root_login})"
}

write_fail2ban_jail() {
  local dest_dir
  dest_dir="$(dirname "$FAIL2BAN_JAIL")"
  mkdir -p "$dest_dir"
  cat > "$FAIL2BAN_JAIL" <<'EOF'
# Managed by deploy/production/harden-ssh.sh.
# Do not add ignoreip GitHub ranges here — they rotate.
[sshd]
enabled = true
port = ssh
filter = sshd
backend = systemd
maxretry = 5
findtime = 600
bantime = 3600
EOF
  chmod 644 "$FAIL2BAN_JAIL"
  log "Wrote $FAIL2BAN_JAIL"
}

install_fail2ban() {
  if command -v fail2ban-client >/dev/null 2>&1; then
    log "fail2ban already installed"
    return
  fi
  if [ "$SKIP_APT" = "1" ]; then
    log "SKIP_APT=1; not installing fail2ban"
    return
  fi
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y fail2ban
  else
    log "WARNING: apt-get not found; install fail2ban by hand"
  fi
}

restart_services() {
  if [ "$SKIP_SERVICE_RESTART" = "1" ]; then
    return
  fi
  if command -v sshd >/dev/null 2>&1; then
    sshd -t
  fi
  if command -v systemctl >/dev/null 2>&1; then
    systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true
    systemctl enable --now fail2ban 2>/dev/null || true
    systemctl reload fail2ban 2>/dev/null || true
  fi
}

write_sshd_dropin
install_fail2ban
write_fail2ban_jail
restart_services
log "SSH hardening applied. Port 22 stays open for GitHub Actions; auth is key-only + fail2ban."
