#!/usr/bin/env bash
# LEGACY Hostinger first-boot (systemd + Nginx + host Redis).
# Canonical production is backend/docker-compose.yml + Traefik.
# See backend/HOSTINGER_DEPLOYMENT.md and deploy/hostinger/README.md.
#
# Refuses to run unless FORCE_LEGACY_SYSTEMD=1 so a new VPS cannot install
# Nginx on :80/:443 next to Traefik by accident.
set -euo pipefail

if [[ "${FORCE_LEGACY_SYSTEMD:-}" != "1" ]]; then
  echo "This script installs the OLD systemd + Nginx stack." >&2
  echo "Canonical production: backend/docker-compose.yml (Traefik)." >&2
  echo "If you really need the legacy layout: FORCE_LEGACY_SYSTEMD=1 $0" >&2
  exit 1
fi

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get upgrade -y
apt-get install -y \
  openjdk-21-jre-headless \
  redis-server \
  nginx \
  certbot \
  python3-certbot-nginx \
  ufw \
  curl \
  git \
  unzip \
  ca-certificates

id -u gpstore >/dev/null 2>&1 || useradd --system --create-home --home-dir /opt/gpstore --shell /usr/sbin/nologin gpstore
install -d -o gpstore -g gpstore -m 0750 /opt/gpstore
install -d -o root -g root -m 0755 /var/www/certbot

echo "Packages installed. Next: copy env.production, the jar, systemd unit, nginx site."
echo "Java: $(java -version 2>&1 | head -n1)"
echo "Do not open port 8081 or 6379 on the firewall."
