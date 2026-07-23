# How to move off `ddl-auto=update` safely

**Update:** the Flyway dependencies are now actually in `pom.xml` (they weren't
before - an earlier version of this doc assumed they existed when they
didn't, my mistake). Flyway is on the classpath but still disabled by default
(`FLYWAY_ENABLED=false`) until you complete the steps below.

Right now Hibernate auto-generates and mutates your schema (`ddl-auto=update`).
That's fine for local dev, but risky in anything shared/production: it can
silently alter columns and never rolls back. Here's the safe way to switch to
real Flyway migrations, using YOUR actual running database as the source of
truth (not a hand-written guess):

1. Run the app locally against your dev Postgres at least once with
   `ddl-auto=update` so Hibernate has created the full current schema.

2. Dump that real schema (structure only, no data):
   ```
   pg_dump -h localhost -U <your_user> -d gpstore --schema-only \
     --no-owner --no-privileges -f V1__baseline.sql
   ```

3. Put that file here: `src/main/resources/db/migration/V1__baseline.sql`

4. In `application.properties`, set:
   ```
   FLYWAY_ENABLED=true
   DDL_AUTO=validate
   ```
   `validate` means Hibernate will only check your entities match the schema,
   never silently change it. Any future schema change becomes a new
   `V2__description.sql`, `V3__description.sql` file — a real, reviewable,
   rollback-able history instead of implicit auto-migration.

5. From then on: every entity change gets a matching new migration file
   checked into git alongside it. That's what makes schema changes safe to
   deploy to a real production database.

I didn't fabricate a V1 migration file directly because guessing the exact
column types/constraints for 20+ linked entities without a live database to
verify against is exactly the kind of confident-but-wrong output that causes
real data loss. Generating it from your actual schema (step 2) is the
correct and safe way to do this.
