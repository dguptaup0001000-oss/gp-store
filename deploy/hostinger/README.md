# LEGACY — not the production deploy path

**Canonical production** is Docker Compose + Traefik:

- Runbook: [`backend/HOSTINGER_DEPLOYMENT.md`](../../backend/HOSTINGER_DEPLOYMENT.md)
- Compose: [`backend/docker-compose.yml`](../../backend/docker-compose.yml)
- Env template: [`backend/.env.example`](../../backend/.env.example)

This folder is the older systemd + host Nginx + host Redis layout. Keep it
only as rollback documentation. **Do not** enable Nginx or the systemd unit
on a VPS that is already running Traefik. Two reverse proxies on :80/:443
will fail Let's Encrypt and drop the API.

Do not run `install.sh` unless you are deliberately recovering that old
layout (`FORCE_LEGACY_SYSTEMD=1`).
