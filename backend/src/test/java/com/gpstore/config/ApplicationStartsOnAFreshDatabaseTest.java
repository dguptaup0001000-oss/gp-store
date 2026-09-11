package com.gpstore.config;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE APPLICATION STARTS AGAINST A SCHEMA FLYWAY BUILT FROM NOTHING.
 *
 * <p>WHY IT IS A TEST AND NOT JUST A SCRIPT. Running the migrations proves the
 * scripts execute. It does not prove the schema they produced is the one the
 * code expects - and the two have come apart twice in this project already,
 * both times over a column declared SMALLINT that Hibernate wanted INTEGER,
 * both times invisible until something validated. Starting the real context
 * with {@code ddl-auto=validate} is what catches that, because Hibernate then
 * compares every entity against every table and refuses to start on a
 * mismatch.
 *
 * <p>IT IS DRIVEN BY {@code scripts/verify/fresh_database.sh}, which points it
 * at an empty database it has just created and migrated. Run on its own
 * against the ordinary test database it still passes and still means
 * something - just less: it says the entities match THAT schema. The script
 * is what makes the schema a fresh one.
 *
 * <p>NOTHING IS ASSERTED ABOUT DATA. A fresh database has none, and a test
 * that needed a seeded row would be testing the seeder.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class ApplicationStartsOnAFreshDatabaseTest {

    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("the context starts and the entities match the schema on disk")
    void itStarts() {
        // Reaching this line at all is most of the test: with
        // ddl-auto=validate a schema mismatch fails the context before any
        // test method runs.
        assertNotNull(entityManager);

        int entities = entityManager.getMetamodel().getEntities().size();
        assertTrue(entities > 40,
                "Only " + entities + " entities were mapped, which is too few for this "
                        + "application - the context may have started with half the "
                        + "persistence unit missing rather than validating it.");
    }
}
