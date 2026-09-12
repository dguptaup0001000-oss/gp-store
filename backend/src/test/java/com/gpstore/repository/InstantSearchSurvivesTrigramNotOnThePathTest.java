package com.gpstore.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INSTANT SEARCH MUST NOT 500 WHERE pg_trgm IS NOT ON THE SEARCH PATH.
 *
 * <p>WHAT HAPPENED. Every {@code /api/products/search/instant} call on
 * production answered HTTP 500, for a customer typing into the search box,
 * while the whole test suite passed and the same catalogue answered 200 on a
 * developer machine. The first production smoke run found it in 46 seconds.
 *
 * <p>WHY THE FALLBACK DID NOT SAVE IT. The repository ran the trigram query,
 * caught the error, and retried with ILIKE ranking. That reads as careful and
 * is in fact impossible: PostgreSQL aborts the entire transaction when a
 * statement fails, so the retry hit SQLSTATE 25P02 - "current transaction is
 * aborted, commands ignored until end of transaction block" - and died too.
 * The exception then surfaced through {@code @Cacheable(sync = true)} as a
 * {@code ValueRetrievalException} and became a 500. A fallback that runs
 * inside the transaction the failure poisoned is not a fallback.
 *
 * <p>WHY ONLY PRODUCTION. {@code %} and {@code similarity()} resolve only if
 * pg_trgm's schema is on the search_path. Production's pg_trgm lives in schema
 * {@code extensions} - the very fact migration V28 exists to accommodate for
 * the trigram INDEX - while a developer database and CI both have it in
 * {@code public}. So the trigram path always worked everywhere it was tested.
 *
 * <p>WHAT IS ASSERTED HERE. The repository now ASKS whether the operator
 * resolves before using it, via {@code pg_operator_is_visible}, which is an
 * ordinary catalogue read and cannot poison anything. This test drives that
 * same question against a search_path that hides the extension - the
 * production condition, reproduced - and requires the answer to be "no".
 * Without that, the code would take the trigram path and 500 again.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Instant search survives pg_trgm not being on the search path")
class InstantSearchSurvivesTrigramNotOnThePathTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductBrowseRepository products;

    /** Exactly the question the repository asks before ranking by similarity. */
    private static final String IS_VISIBLE = """
            SELECT EXISTS (
                SELECT 1 FROM pg_operator o
                 WHERE o.oprname = '%'
                   AND o.oprleft = 'text'::regtype
                   AND o.oprright = 'text'::regtype
                   AND pg_catalog.pg_operator_is_visible(o.oid))
            """;

    @Test
    @DisplayName("the probe says no when the extension's schema is off the path")
    @Transactional
    void theProbeDetectsTheProductionCondition() {
        // SET LOCAL, so this dies with the test's transaction and no other
        // test can see it. The probe reads only pg_catalog, which is always
        // implicitly on the path, so it still answers with public hidden.
        jdbc.execute("SET LOCAL search_path TO pg_catalog");

        Boolean visible = jdbc.queryForObject(IS_VISIBLE, Boolean.class);
        assertFalse(Boolean.TRUE.equals(visible),
                "With the extension's schema off the search_path the trigram operator does NOT "
                        + "resolve, and the repository must find that out by asking rather than by "
                        + "running a query that aborts the transaction. If this says it is visible, "
                        + "the probe is not testing visibility and production will 500 again.");
    }

    @Test
    @DisplayName("control: on this database the operator IS visible")
    void theProbeSaysYesHere() {
        // Without this the assertion above would pass on a database with no
        // pg_trgm at all, proving nothing about the search_path.
        Boolean visible = jdbc.queryForObject(IS_VISIBLE, Boolean.class);
        assertTrue(Boolean.TRUE.equals(visible),
                "pg_trgm should be installed and visible on the test database - V5 installs it. "
                        + "If it is not, TrigramExtensionIsInstalledBeforeItIsUsedTest explains why "
                        + "that matters and this control is the wrong place to fix it.");
    }

    @Test
    @DisplayName("the probe is not fooled by the built-in numeric modulo operator")
    void numericModuloIsNotTrigram() {
        // '%' is also integer modulo, which exists on every PostgreSQL ever
        // built. Without pinning the operand types to text the probe answers
        // "yes" on a database with no pg_trgm and sends production straight
        // back into the failing query.
        Integer anyModulo = jdbc.queryForObject(
                "SELECT count(*) FROM pg_operator WHERE oprname = '%'", Integer.class);
        assertTrue(anyModulo != null && anyModulo > 1,
                "expected several '%' operators (numeric modulo plus trigram); found " + anyModulo);

        Integer textOnly = jdbc.queryForObject("""
                SELECT count(*) FROM pg_operator
                 WHERE oprname = '%'
                   AND oprleft = 'text'::regtype AND oprright = 'text'::regtype
                """, Integer.class);
        assertEquals(1, textOnly,
                "the text/text '%' is pg_trgm's and there should be exactly one of it");
    }

    @Test
    @DisplayName("instant search answers rather than throwing")
    void searchStillAnswers() {
        // The end the whole thing is for. Whichever ranking path this database
        // takes, a customer typing in the search box gets a result set.
        ProductBrowseRepository.SearchPage page = products.searchInstant("dal", 0, 5);
        assertTrue(page.totalElements() >= 0, "search returned no page at all");
    }
}
