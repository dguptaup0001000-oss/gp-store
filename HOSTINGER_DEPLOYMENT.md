# GP-STORE on Hostinger

**Canonical production:** Docker Compose + Traefik on a Hostinger KVM 2 VPS.

```
https://api.gpstore.co.in
  → Traefik :443
  → Spring Boot :8081 (private)
  → PostgreSQL :5432 (private) + Redis :6379 (private)
```

Exact commands: **[`backend/HOSTINGER_DEPLOYMENT.md`](backend/HOSTINGER_DEPLOYMENT.md)**

- Compose: `backend/docker-compose.yml`
- Env template: `backend/.env.example`
- Laptop published-ports Compose: `backend/docker-compose.dev.yml` (not for the VPS)

Do not use Render. Do not publish `5432`, `6379`, or `8081`.

`deploy/hostinger/` is **legacy** systemd + Nginx. Do not enable it next to Traefik.
See [`deploy/hostinger/README.md`](deploy/hostinger/README.md).
