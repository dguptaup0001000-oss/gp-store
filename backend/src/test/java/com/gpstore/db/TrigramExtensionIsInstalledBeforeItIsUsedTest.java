package com.gpstore.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TRIGRAM EXTENSION IS INSTALLED BY A MIGRATION, BEFORE A MIGRATION NEEDS
 * IT - AND NOBODY HAD TO SET UP THEIR DATABASE BY HAND.
 *
 * <p>WHY THIS EXISTS. A red CI build was diagnosed from this line in the
 * Postgres service-container log:
 *
 * <pre>ERROR: operator class "gin_trgm_ops" does not exist for access method "gin"</pre>
 *
 * <p>The reading was that CI's database lacks {@code pg_trgm} while the
 * developer's has it, and that the fix was a new migration running
 * {@code CREATE EXTENSION}. Both halves were wrong, and acting on either would
 * have done damage:
 *
 * <ul>
 *   <li>V5 has installed {@code pg_trgm} since long before that build. Nobody's
 *       database is hand-configured; a genuinely empty one gets the extension
 *       from Flyway like everything else. {@link TheDatabase} proves that.</li>
 *   <li>That log line is an ASSERTION PASSING, not a failure. {@code
 *       FlywayV28ApplyTest} builds a probe database with {@code pg_trgm} in
 *       schema {@code extensions}, runs V27's unqualified statement, and
 *       requires it to fail - that is how it proves V28 was needed. The error
 *       reaches the shared server log because the probe shares the server.</li>
 *   <li>A second {@code CREATE EXTENSION} would duplicate V5 and, worse, break
 *       deploys where the database role may not create extensions - the reason
 *       {@code FlywayV28ScriptTest} forbids it in V28. Production's pg_trgm is
 *       a Supabase dump living in schema {@code extensions}, installed by
 *       somebody with rights the application role does not have.</li>
 * </ul>
 *
 * <p>So this test is the answer to a question that cost a day: is the trigram
 * setup actually broken on a fresh database? It is not, and now that is
 * something the suite states rather than something a person re-derives from a
 * database log at the wrong moment.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("pg_trgm is installed by a migration, before a migration needs it")
class TrigramExtensionIsInstalledBeforeItIsUsedTest {

    /** Migrations that may use the trigram operator class or the % operator. */
    private static final Pattern TRIGRAM_USE =
            Pattern.compile("gin_trgm_ops|\\bsimilarity\\s*\\(|\\bshow_trgm\\s*\\(",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATES_TRGM =
            Pattern.compile("CREATE\\s+EXTENSION[^;]*pg_trgm", Pattern.CASE_INSENSITIVE);

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * SQL with its comments removed.
     *
     * <p>WITHOUT THIS THE SCAN READS PROSE AS CODE. V26, V27 and V28 each carry
     * the line "Do not CREATE EXTENSION pg_trgm here" - a warning to the next
     * author, and exactly the string a naive search reports as a second
     * CREATE EXTENSION. The first run of this test failed for that reason and
     * for no other, which is a good argument for the control test below.
     */
    private static String statementsOf(Path file) throws IOException {
        String sql = Files.readString(file);
        sql = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(sql).replaceAll(" ");
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the database this suite just ran against")
    class TheDatabase {

        @Autowired private JdbcTemplate jdbc;

        @Test
        @DisplayName("has pg_trgm, installed by Flyway rather than by hand")
        void extensionIsPresent() {
            Integer installed = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
            assertEquals(1, installed,
                    "pg_trgm is missing. V5 installs it, so either V5 did not run or something "
                            + "dropped it afterwards - do NOT answer this by installing the "
                            + "extension by hand, and do NOT add a second CREATE EXTENSION "
                            + "migration: find out why V5's did not stick.");
        }

        @Test
        @DisplayName("can resolve the gin_trgm_ops operator class")
        void operatorClassResolves() {
            Integer opclasses = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_opclass oc
                      JOIN pg_am am ON am.oid = oc.opcmethod
                     WHERE oc.opcname = 'gin_trgm_ops' AND am.amname = 'gin'
                    """, Integer.class);
            assertTrue(opclasses != null && opclasses >= 1,
                    "no gin_trgm_ops operator class for access method gin - every trigram index "
                            + "in this schema is unbuildable");
        }

        @Test
        @DisplayName("carries every trigram index the migrations declare")
        void everyTrigramIndexExists() {
            List<String> present = jdbc.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE indexname LIKE '%trgm%'", String.class);

            // V5 builds the first two, V27/V28 the second two. If a later
            // migration adds another, add it here rather than loosening this.
            for (String expected : List.of(
                    "idx_products_name_trgm",
                    "idx_products_brand_trgm",
                    "idx_products_search_keywords_trgm",
                    "idx_products_subcategory_trgm")) {
                assertTrue(present.contains(expected),
                        expected + " is missing; the database has " + present);
            }
        }

        @Test
        @DisplayName("applied V5 before V27, and applied both successfully")
        void theOrderingHeldInPractice() {
            // The file ordering is checked below; this is the same claim made
            // against what Flyway actually did to THIS database.
            Integer failures = jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
            assertEquals(0, failures, "a migration is recorded as failed");

            Integer v5Rank = jdbc.queryForObject(
                    "SELECT installed_rank FROM flyway_schema_history WHERE version = '5'",
                    Integer.class);
            Integer v27Rank = jdbc.queryForObject(
                    "SELECT installed_rank FROM flyway_schema_history WHERE version = '27'",
                    Integer.class);
            assertTrue(v5Rank != null && v27Rank != null,
                    "V5 and V27 must both be in the history");
            assertTrue(v5Rank < v27Rank,
                    "V5 installs pg_trgm and V27 uses it; V5 ran at rank " + v5Rank
                            + " and V27 at " + v27Rank);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the migration files")
    class TheFiles {

        @Test
        @DisplayName("install pg_trgm exactly once, and it is V5")
        void oneCreateExtensionAndItIsV5() throws IOException {
            List<String> creators = new ArrayList<>();
            for (Path file : migrations()) {
                if (CREATES_TRGM.matcher(statementsOf(file)).find()) {
                    creators.add(file.getFileName().toString());
                }
            }
            assertEquals(List.of("V5__add_search_trigram_indexes.sql"), creators,
                    "pg_trgm must be created by exactly one migration, and it is V5. A second "
                            + "CREATE EXTENSION is not a harmless duplicate: on a deployment whose "
                            + "database role may not create extensions it fails the migration and "
                            + "rolls the release back, which is why FlywayV28ScriptTest forbids it "
                            + "in V28. Found: " + creators);
        }

        @Test
        @DisplayName("never use the trigram opclass in a migration older than V5")
        void nothingUsesTrigramsBeforeTheyExist() throws IOException {
            List<String> tooEarly = new ArrayList<>();
            for (Path file : migrations()) {
                String name = file.getFileName().toString();
                if (versionOf(name) >= 5) {
                    continue;
                }
                if (TRIGRAM_USE.matcher(statementsOf(file)).find()) {
                    tooEarly.add(name);
                }
            }
            assertTrue(tooEarly.isEmpty(),
                    "these migrations use trigram features before V5 installs pg_trgm, so they "
                            + "cannot run on a fresh database: " + tooEarly);
        }

        @Test
        @DisplayName("control: the scanner does find the trigram use it is looking for")
        void theScannerWorks() throws IOException {
            // Without this, a broken regex would make both tests above pass by
            // finding nothing at all.
            Path v27File = MIGRATIONS.resolve("V27__search_keyword_trigram_indexes.sql");
            assertTrue(TRIGRAM_USE.matcher(statementsOf(v27File)).find(),
                    "the scanner cannot see gin_trgm_ops in V27's statements, so it proves "
                            + "nothing elsewhere");

            // V5's statement really is there, and is seen.
            assertTrue(CREATES_TRGM.matcher(
                            statementsOf(MIGRATIONS.resolve("V5__add_search_trigram_indexes.sql")))
                            .find(),
                    "the scanner cannot see V5's CREATE EXTENSION, so 'exactly one' means nothing");

            // And the comment that says not to do it is NOT read as doing it.
            assertTrue(Files.readString(v27File).contains("Do not CREATE EXTENSION"),
                    "this control assumes V27 carries that warning comment");
            assertFalse(CREATES_TRGM.matcher(statementsOf(v27File)).find(),
                    "the scanner is reading V27's warning comment as a statement");
        }
    }

    // ------------------------------------------------------------------

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static int versionOf(String fileName) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(fileName);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
