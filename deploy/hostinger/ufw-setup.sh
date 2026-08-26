#!/usr/bin/env bash
# Public ports only: SSH, HTTP, HTTPS. Redis and Spring Boot stay on localhost.
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
ufw status verbose
echo "Confirmed: 8081 and 6379 must NOT appear as allowed."
