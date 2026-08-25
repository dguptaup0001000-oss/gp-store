# Deploying GP-STORE

**Production is a Hostinger KVM 2 VPS (Spring Boot + Nginx + Redis) +
Supabase (Postgres). Render is not used.**

- Exact VPS commands (Java 21, Redis localhost, Nginx, systemd, HTTPS,
  rollback, backup): **[HOSTINGER_DEPLOYMENT.md](HOSTINGER_DEPLOYMENT.md)**
- Operator checklist, including the manual `DDL_AUTO=validate` step:
  **[PRODUCTION_CHECKLIST.md](PRODUCTION_CHECKLIST.md)**
- Flyway / empty-database CI (`schema-migrate`):
  **[backend/src/main/resources/db/migration/README.md](backend/src/main/resources/db/migration/README.md)**

This page used to describe Railway, then Render. Neither hosts the app.
Old links to `DEPLOYMENT.md` should land here.
