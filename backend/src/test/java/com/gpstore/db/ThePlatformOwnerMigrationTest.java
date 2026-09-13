package com.gpstore.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V66 grants the platform owner SUPER_ADMIN, and can never grant it twice.
 *
 * WHY A MIGRATION GETS A TEST AT ALL. This one hands out the most privileged
 * row in the database. Flyway has already run by the time any test starts, so
 * this reads the statement out of the migration file and runs it against
 * fixtures instead - which also means the test cannot drift from the file: if
 * somebody rewrites the SQL, the test exercises the rewrite.
 *
 * The guard is the whole point. PlatformStaffService refuses to open a
 * SUPER_ADMIN account deliberately, because a second platform owner should be
 * a considered act rather than an API call. A migration that could quietly
 * add one would take that promise back.
 *
 * TRANSACTIONAL, AND ROLLED BACK. Two reasons, both learned the hard way on
 * the first run. The shared test database already carries SUPER_ADMIN rows
 * left by other suites' fixtures, so the guard - correctly - refused to do
 * anything at all; the precondition has to be established per test. And
 * establishing it means demoting those rows, which is not something to do to
 * a database other tests are using. Inside a transaction that always rolls
 * back, both problems disappear and nothing this class touches survives it.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("V66 promotes exactly one platform owner, once")
@Transactional
class ThePlatformOwnerMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V66__the_platform_owner_holds_super_admin.sql");

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> made = new ArrayList<>();

    /**
     * Clears the way, inside the rolled-back transaction.
     *
     * Other suites leave SUPER_ADMIN fixtures behind, and with any of them
     * present the migration's NOT EXISTS guard makes it a no-op - which is
     * the guard being right, not a bug. Tests about what the promotion DOES
     * call this first; the test about the guard itself deliberately does not.
     */
    private void withNoPlatformOwnerYet() {
        jdbc.update("UPDATE customers SET role = 'ADMIN' "
                + "WHERE role IN ('SUPER_ADMIN', 'PLATFORM_ADMIN')");
    }

    /** The statement, with every comment line removed. */
    private static String promotion() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        return Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }

    /**
     * The address the migration actually names, read from the file.
     *
     * NOT COPIED HERE. A literal in both places would let the migration be
     * pointed at a different account while this test kept passing against the
     * old one - which is the one mistake that would matter.
     */
    private static String ownerEmail() throws IOException {
        Matcher m = Pattern.compile("lower\\('([^']+)'\\)")
                .matcher(Files.readString(MIGRATION, StandardCharsets.UTF_8));
        assertTrue(m.find(), "the migration must name the owner's address");
        return m.group(1);
    }

    private long insertStaff(String email, String role) {
        Long id = jdbc.queryForObject(
                "INSERT INTO customers (full_name, email, role, active, enabled) "
                        + "VALUES (?, ?, ?, TRUE, TRUE) RETURNING id",
                Long.class, "Fixture " + role, email, role);
        made.add(id);
        return id;
    }

    private String roleOf(long id) {
        return jdbc.queryForObject("SELECT role FROM customers WHERE id = ?", String.class, id);
    }

    // No @AfterEach. The class is @Transactional, so every row inserted here
    // and every role demoted by withNoPlatformOwnerYet() is rolled back when
    // the test ends - including on failure, which a manual teardown would
    // have to be written carefully to match.

    @Test
    @DisplayName("the owner's ADMIN account becomes SUPER_ADMIN")
    void itPromotesTheOwner() throws Exception {
        withNoPlatformOwnerYet();
        long owner = insertStaff(ownerEmail(), "ADMIN");

        jdbc.update(promotion());

        assertEquals("SUPER_ADMIN", roleOf(owner));
    }

    @Test
    @DisplayName("the address is matched without regard to case")
    void caseDoesNotDecideWhoOwnsTheMarketplace() throws Exception {
        // A row typed with a capital letter is the same person. Login matches
        // exactly elsewhere, so a mismatch here would be a silent no-op and
        // the owner would still be locked out with nothing to show why.
        withNoPlatformOwnerYet();
        long owner = insertStaff(ownerEmail().toUpperCase(), "ADMIN");

        jdbc.update(promotion());

        assertEquals("SUPER_ADMIN", roleOf(owner));
    }

    @Test
    @DisplayName("it does nothing once somebody already holds the role")
    void itCannotMintASecondPlatformOwner() throws Exception {
        withNoPlatformOwnerYet();
        long alreadyOwner = insertStaff("someone-else-" + System.nanoTime() + "@example.test",
                "SUPER_ADMIN");
        long candidate = insertStaff(ownerEmail(), "ADMIN");

        jdbc.update(promotion());

        // THE GUARD. A second platform owner is meant to be a deliberate act,
        // and a migration that re-ran after one existed must not make one.
        assertEquals("ADMIN", roleOf(candidate));
        assertEquals("SUPER_ADMIN", roleOf(alreadyOwner));
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void itIsIdempotent() throws Exception {
        withNoPlatformOwnerYet();
        long owner = insertStaff(ownerEmail(), "ADMIN");

        assertEquals(1, jdbc.update(promotion()));
        assertEquals(0, jdbc.update(promotion()),
                "a second run must touch no rows at all");
        assertEquals("SUPER_ADMIN", roleOf(owner));
    }

    @Test
    @DisplayName("another shop owner is left exactly as they were")
    void itLeavesEveryOtherMerchantAlone() throws Exception {
        withNoPlatformOwnerYet();
        long merchant = insertStaff("merchant-" + System.nanoTime() + "@example.test", "ADMIN");

        jdbc.update(promotion());

        // The failure this forbids is the worst one available: handing the
        // whole marketplace to a shop owner who never asked for it.
        assertEquals("ADMIN", roleOf(merchant));
    }

    @Test
    @DisplayName("a CUSTOMER with that address is not promoted either")
    void onlyAnAdminIsPromoted() throws Exception {
        withNoPlatformOwnerYet();
        // The owner's account is an ADMIN today. If some future row carried
        // the same address as a plain shopper, it is not the platform owner.
        long shopper = insertStaff(ownerEmail(), "CUSTOMER");

        jdbc.update(promotion());

        assertEquals("CUSTOMER", roleOf(shopper));
    }
}
