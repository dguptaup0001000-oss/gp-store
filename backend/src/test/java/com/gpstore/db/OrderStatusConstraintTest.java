package com.gpstore.db;

import com.gpstore.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A guard on the trap that adding an enum value sets.
 *
 * WHAT HAPPENED. OrderStatus gained PACKED so a worker's scan could record it.
 * Everything compiled, every test on a freshly-created database passed, and
 * then every write of the new value failed:
 *
 *     new row for relation "orders" violates check constraint
 *     "orders_order_status_check"
 *
 * Hibernate generates a CHECK constraint listing every value a string enum had
 * WHEN THE COLUMN WAS CREATED, and ddl-auto=update never alters it afterwards.
 * So a new value works perfectly on any database made after the change and
 * fails on every database made before it - which is the definition of
 * production.
 *
 * WHY THIS TEST IS SHAPED LIKE THIS. Asserting "PACKED can be saved" would
 * pass trivially on CI's fresh database and catch nothing. Reading the
 * constraint the database actually holds and comparing it against the enum the
 * code actually has is the same check on every database, and it names the
 * missing value rather than leaving someone to decode a constraint violation.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class OrderStatusConstraintTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("the database accepts every order status the code can produce")
    void everyEnumValueIsAllowedByTheCheckConstraint() {
        List<String> definitions = jdbc.queryForList("""
                SELECT pg_get_constraintdef(con.oid)
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                WHERE rel.relname = 'orders'
                  AND con.contype = 'c'
                  AND pg_get_constraintdef(con.oid) ILIKE '%order_status%'
                """, String.class);

        if (definitions.isEmpty()) {
            // No constraint at all is a valid state - V20 drops and recreates
            // it, and some databases will be between the two. Nothing to check.
            return;
        }

        String combined = String.join(" ", definitions);

        List<String> missing = new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) {
            if (!combined.contains("'" + status.name() + "'")) {
                missing.add(status.name());
            }
        }

        assertTrue(missing.isEmpty(),
                "OrderStatus has value(s) " + missing + " that this database's check constraint "
                        + "does not permit, so writing one fails at runtime. Hibernate will not "
                        + "widen an existing constraint - add a migration that drops and recreates "
                        + "it, as V20 does. Constraint is: " + combined);
    }

    @Test
    @DisplayName("PACKED in particular, since that is the one the worker scan writes")
    void packedIsWritable() {
        // Belt and braces on the value this feature depends on. If the
        // constraint check above is ever weakened, this still fails.
        Long allowed = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid "
                        + "WHERE rel.relname = 'orders' AND con.contype = 'c' "
                        + "AND pg_get_constraintdef(con.oid) ILIKE '%order_status%' "
                        + "AND pg_get_constraintdef(con.oid) NOT ILIKE '%PACKED%'",
                Long.class);

        assertEquals(0L, allowed == null ? 0L : allowed,
                "a check constraint on orders.order_status exists that does not permit PACKED - "
                        + "every worker pack scan against this database would fail");
    }
}
