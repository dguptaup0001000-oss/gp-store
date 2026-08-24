package com.gpstore.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Second half of the empty-database proof: the same Postgres that
 * {@link EmptyDatabaseBootstrapTest} just built, now started the way
 * production should run — Flyway first, Hibernate {@code validate} only.
 *
 * {@code FlywayAfterSchemaConfig} stands aside under validate, which is what
 * made schema changes deployable. If this context fails to start, the live
 * schema after V2–current does not match the JPA entities, and
 * {@code DDL_AUTO=validate} would fail a deploy the same way.
 *
 * Must run AFTER {@link EmptyDatabaseBootstrapTest} against the same database.
 * The {@code schema-migrate} CI job enforces that with two sequential Maven
 * invocations. Running this test alone against an empty database is expected
 * to fail (V2 cannot index tables that do not exist).
 */
@Tag("schema-bootstrap")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class ProductionSchemaValidateTest {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("ddl-auto=validate accepts the schema Flyway just built")
    void hibernateValidateAcceptsMigratedSchema() {
        SchemaBootstrapAssertions.assertEveryVersionedMigrationSucceeded(flyway);
    }
}
