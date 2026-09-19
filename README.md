# GP-STORE

A multi-shop grocery and kirana marketplace for India. Merchants run their own
storefronts; customers see the shops that will actually deliver to their
address, and buy from them.

This is a production system with real merchants and real money in it. That
fact sets the tone for everything below.

---

## What is here

| Path | What it is |
|---|---|
| `backend/` | Spring Boot 3.5 / Java 21 API. 534 source files, 358 endpoints. |
| `frontend/` | One Flutter project, four apps (customer, merchant admin, super admin, delivery worker) built from separate entrypoints. |
| `backend/src/main/resources/db/migration/` | Flyway migrations, V1 upward. |
| `load-tests/` | k6 scripts and the capacity measurements, with their results written down. |
| `scripts/` | Deployment and verification shell scripts. |
| `docs/` | Architecture notes and point-in-time reports. |
| `.github/workflows/` | CI, release, deploy, backup, uptime and smoke workflows. |

The four Flutter apps share one codebase and differ by entrypoint:

```
frontend/lib/customer_main.dart      the shopper's app
frontend/lib/admin_main.dart         the merchant's app
frontend/lib/super_admin_main.dart   the platform operator's app
frontend/lib/worker_main.dart        the delivery rider's app
```

---

## The one thing to understand first

**A shop's data belongs to that shop.** GP-STORE is multi-tenant, and the
tenant is the shop. This is not a convention - it is enforced in three places
at once, and a change that weakens any of them should not be merged:

1. **`TenantResolver`** decides which shop the current request acts for, from
   the credential and the database, never from anything the client sent.
2. **A Hibernate `@Filter`** narrows every query on a `ShopOwned` entity (25 of
   them) to that shop, and **`TenantEntityListener`** stamps every insert.
3. **`ShopMembership`** answers "may this account act for this shop", read live
   from `shop_staff` rather than from a claim inside a token.

Things that bypass the filter need an explicit predicate written by hand:
native queries (`@Query(nativeQuery = true)`) and bulk JPQL updates.
`ShopScopeIsNotOptionalTest` will fail the build if you add one without
registering it with a note explaining why it is safe. That test is doing you a
favour; read its list before adding to it.

`EveryPublishedEndpointSweepTest` calls every route the application publishes
as a second merchant and asserts nothing leaks.

---

## Running it

### Backend

Needs PostgreSQL and Redis on localhost.

```bash
cd backend
./mvnw spring-boot:run          # serves http://localhost:8081/v1
```

An empty database bootstraps itself: Hibernate creates the domain tables
(Flyway's scripts start at V2 and assume they exist), then Flyway runs.
`FlywayAfterSchemaConfig` handles that ordering when `ddl-auto=update`.

### Apps

```bash
cd frontend
flutter run -t lib/customer_main.dart
```

Flutter is pinned to **3.35.7** in CI; use the same locally.

---

## Testing

```bash
cd backend
./mvnw test                                    # ~2,100 tests
./mvnw verify -Pcoverage                       # + a JaCoCo report
./mvnw test -Plarge-marketplace -Dtest=LargeMarketplaceTest
cd ../frontend && flutter test                 # ~875 tests
```

Two suites are tagged out of the default run because they are experiments
rather than regression tests:

- **`schema-bootstrap`** - boots an empty database and asserts every migration
  applies. Run by the `schema-migrate` CI job.
- **`large-marketplace`** - builds 2,115 shops across 100 trades with 262,451
  listings and checks isolation across them. Run weekly and on pull requests
  that touch the generator, the tenant machinery or the migrations, by the
  `large-marketplace` workflow.

### Generating test data

`MarketplaceTestData` refuses to run unless **all three** hold: the application
says it is not production (`app.production=false`), the database is on
loopback, and an explicit opt-in is set. It is not possible to point it at
production by accident.

---

## Capacity, honestly

Measured on one 4-vCPU box with the load generator sharing it, against 2,115
shops. Full method and numbers in [`load-tests/README.md`](load-tests/README.md).

- **~1,000 concurrent browsers** within a p95 < 2 s budget.
- Above that the application sheds load deliberately (503 with `Retry-After`)
  rather than failing; at 4,000 virtual users it still serves ~62% and refuses
  the rest on purpose.
- The limit is connection-pool residency on one instance, not CPU and not
  PostgreSQL - both sat idle at the ceiling.

A refused customer is not a served customer, and the load-test reports are
written to keep those two numbers apart.

---

## Deploying

`main` deploys automatically. `Deploy Production` ships the backend to the VPS
and then runs `production-smoke.yml` against the live API, which walks the
marketplace over real HTTP and tries to break the tenant boundary by hand. A
red smoke run means look immediately; it has already caught a customer-facing
500 that the health check could not see.

See [`DEPLOYMENT.md`](DEPLOYMENT.md) and
[`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md).

---

## House rules

These are not style preferences; each one is here because something went wrong
without it.

- **Never trust a shop id from a client.** `X-Shop-Id` may narrow a scope the
  credential already permits. It may never grant one.
- **Never add a database default for `shop_id`.** A forgotten insert must fail
  loudly, not file itself under Shop #1.
- **Never edit an applied migration.** Add a new one.
- **Never log** a password, OTP, token, `Authorization` header, or payment
  secret.
- **An index earns its place on a measured query**, not on a hunch and not on a
  test fixture's teardown.
- **Do not widen a connection pool to make a load stage pass.** Find out what
  is holding the connections.
