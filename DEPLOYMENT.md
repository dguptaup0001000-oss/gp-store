# Deploying GP-STORE

**Production is Render (app) + Supabase (Postgres), not Railway.**

- Operator checklist, including the manual `DDL_AUTO=validate` Render step
  and its rollback: **[PRODUCTION_CHECKLIST.md](PRODUCTION_CHECKLIST.md)**
- Render service setup (placeholders only, no live credentials):
  **[backend/DEPLOYMENT.md](backend/DEPLOYMENT.md)**
- Flyway / empty-database CI (`schema-migrate`):
  **[backend/src/main/resources/db/migration/README.md](backend/src/main/resources/db/migration/README.md)**

This page used to describe Railway. That host is not used. Old links to
`DEPLOYMENT.md` should land here and follow the files above.
