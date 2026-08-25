#!/usr/bin/env bash
# Hostinger KVM 2 first-boot packages + users for GP-STORE.
# Run as root on Ubuntu LTS. Does NOT start the shop until env.production
# is filled and the jar is in place — see HOSTINGER_DEPLOYMENT.md.
set -euo pipefail

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
