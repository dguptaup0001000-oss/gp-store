#!/usr/bin/env bash
# Safe Linux tuning for the Hostinger KVM 2 (2 vCPU / 8 GB) production host.
# Idempotent. Does not disable security controls, conntrack, or swap.
#
# Apply as root (prepare-vps.sh). deploy.sh calls this best-effort: a
# non-root GitHub Actions user cannot write /etc/sysctl.d, and that is OK —
# Docker ulimits on the Compose services still raise the container nofile.

set -Eeuo pipefail

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

if [ "$(id -u)" -ne 0 ]; then
  log "Not root; skipping host sysctl. Container ulimits in docker-compose.yml still apply."
  exit 0
fi

CONF_SRC="${1:-}"
if [ -z "$CONF_SRC" ]; then
  CONF_SRC="$(cd "$(dirname "$0")" && pwd)/sysctl-gpstore.conf"
fi
if [ ! -f "$CONF_SRC" ]; then
  log "Missing $CONF_SRC"
  exit 1
fi

install -m 0644 "$CONF_SRC" /etc/sysctl.d/99-gpstore.conf
sysctl --system >/dev/null

# Soft/hard nofile for future login shells. Running containers pick up
# Compose ulimits without this; it still helps operator ssh sessions.
if ! grep -q 'gpstore nofile' /etc/security/limits.d/99-gpstore.conf 2>/dev/null; then
  umask 022
  cat > /etc/security/limits.d/99-gpstore.conf <<'EOF'
* soft nofile 65535
* hard nofile 65535
root soft nofile 65535
root hard nofile 65535
EOF
fi

log "Applied /etc/sysctl.d/99-gpstore.conf and nofile 65535"
