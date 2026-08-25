# GP-STORE on Hostinger

**Production is Docker Compose on a Hostinger VPS:** Traefik (HTTPS) →
Spring Boot `:8081` → PostgreSQL + Redis on the Docker network only.

Exact commands: **[`backend/HOSTINGER_DEPLOYMENT.md`](backend/HOSTINGER_DEPLOYMENT.md)**

- Compose file: `backend/docker-compose.yml`
- Env template (placeholders only): `backend/.env.example`
- Local laptop Compose (published ports): `backend/docker-compose.dev.yml`

Do not use Railway or Render. Do not publish `5432`, `6379`, or `8081`
on the public internet.

Optional older systemd + host Nginx files remain under `deploy/hostinger/`
for operators who are not using Compose. New production installs should
follow `backend/HOSTINGER_DEPLOYMENT.md`.
