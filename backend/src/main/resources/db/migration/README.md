# How `ddl-auto` and Flyway actually relate here (and how to turn `ddl-auto=update` off in production)

**Status as of 2026-08-19:** production's live schema has been confirmed
(via a real schema dump, see below) to already match what the JPA entities
expect - `DDL_AUTO=validate` is safe to set in Render's environment
variables. That env var change itself is a manual step in Render's
dashboard, done outside this repo. This file documents the reasoning, in
case the question ever comes up again (a new environment, a new developer,
"why isn't this ddl-auto=update?").

## What actually happened

Two things were true at the same time, and they don't depend on each other
the way an earlier version of this doc assumed:

1. **Flyway has been running in production the whole time.** `FLYWAY_ENABLED`
   defaults to `true` (`application.properties`), and `DEPLOYMENT.md`'s setup
   steps always told you to set it explicitly too. Production's
   `flyway_schema_history` table already has migrations V2-V6 tracked as
   applied (confirmed directly against a real schema dump - see
   `backend/docs/production-schema-reference.sql`). Only CI's test database
   runs with `FLYWAY_ENABLED=false` (see `.github/workflows/ci.yml`) - that's
   a CI-only override, never how production has run.

2. **`ddl-auto=update` was still the default (`DDL_AUTO:update`) alongside
   it.** Flyway owned the handful of things ddl-auto can't reach (indexes,
   the shedlock table, the payments unique constraint, trigram indexes, the
   order-number sequence - V2 through V6). Everything else - every actual
   table `ddl-auto=update` derives from the JPA `@Entity` classes - was never
   Flyway's concern at all; Hibernate created and had been silently free to
   alter it directly against production on every deploy.

**Why an earlier version of this doc got the fix wrong:** it assumed making
`ddl-auto=validate` safe required first getting Flyway to own the entity
tables too - dump the schema, add it as a new migration file, then flip the
setting. That's backwards for a database Flyway is already tracking:

- Production's schema_history already has V2-V6 applied. A *new* file
  numbered lower (a "V1") would be older than migrations Flyway has already
  run - Flyway rejects that by default as out-of-order and refuses to start.
- A new file numbered higher (a "V7") would fail the moment Flyway actually
  tried to run it, since every `CREATE TABLE` in it targets a table that
  already exists - Postgres rejects that as `relation already exists`.

**What `ddl-auto=validate` actually depends on - and it isn't Flyway.** At
startup, Hibernate in `validate` mode just compares the live database
structure against what the `@Entity` classes expect, and refuses to start if
they don't match. It has no dependency on Flyway, `flyway_schema_history`, or
any migration file existing. Since `ddl-auto=update` had been keeping
production's schema in sync with the entities continuously, that check
already passed - no baseline migration was ever actually required to flip it
safely.

## What was done, and what's left

1. **Done:** generated a schema-only dump directly from production
   (`pg_dump --schema=public --schema-only`) to confirm the live schema
   really did match expectations before touching anything - kept at
   `backend/docs/production-schema-reference.sql` as a reference snapshot.
   It is deliberately **not** a Flyway migration file (wrong filename
   pattern, wrong directory) - see that file's own header for why it must
   never be treated as one.
2. **Manual step, outside this repo:** set `DDL_AUTO=validate` in Render's
   environment variables (Render → your service → Environment) and confirm
   the app starts cleanly on the redeploy that triggers. If it fails to
   start, revert `DDL_AUTO` (unset it, or set it back to `update`) and
   redeploy - `validate` mode never writes anything, so there's no data risk
   either way, just a startup check that either passes or doesn't.

Once that's set: any future schema change needs an explicit new Flyway
migration (`V7__description.sql`, `V8__...`, etc.) - `ddl-auto` will never
again silently apply one for you. That's the actual safety improvement this
whole exercise is for.


## Known limitation: migrations alone cannot provision an EMPTY database

Verified directly, not assumed: pointing this app at a brand-new empty
database with `FLYWAY_ENABLED=true` fails on V2, because **no migration
creates the domain tables**. Only V3 (shedlock) and V9 (outbox_events)
contain `CREATE TABLE` at all; every other table exists solely because
`ddl-auto=update` created it during the project's early life, and V2 onward
assume those tables are already there.

This is not a problem for the existing production database - its schema is
already present and its `flyway_schema_history` already records V2 onward.
It is a problem the first time somebody provisions a NEW environment
(a staging copy, a disaster-recovery rebuild), especially now that
production runs `DDL_AUTO=validate` and will refuse to start against a
schema it cannot validate.

**Why this is not fixed by adding a V1 baseline:** production's history
already has V2-V6 applied. Flyway validates on migrate by default, so a
newly-added lower-numbered V1 is reported as "detected resolved migration
not applied to database" and the application refuses to start. Adding V1
would fix fresh installs by breaking the live system - the wrong trade.

**Procedure for a new environment**, until a baseline is introduced
deliberately (which requires coordinating `flyway.baselineVersion` with the
existing production history):

1. Create the empty database and `CREATE EXTENSION pg_trgm;`.
2. Start the app ONCE with `DDL_AUTO=update` and `FLYWAY_ENABLED=false`, so
   Hibernate creates the entity tables.
3. Restart with `FLYWAY_ENABLED=true` and `DDL_AUTO=validate`. Flyway applies
   V2 onward, then Hibernate validates.

Steps 2 and 3 are exactly what was executed to verify this - all eight
migrations applied cleanly and `validate` then passed, which is also the
proof that V7/V8/V9 are consistent with the current entities.

Alternatively, restore `backend/docs/production-schema-reference.sql` into
the empty database first and skip step 2; that file is a real dump of the
live schema and exists for this purpose. It is deliberately NOT a migration
- see its own header.
