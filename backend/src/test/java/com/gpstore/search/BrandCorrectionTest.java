package com.gpstore.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repairing a brand the speech recogniser got wrong.
 *
 * REPORTED FROM A PHONE: a customer said "aachi haldi" three times. Google
 * heard "रांची हल्दी" every time, which romanises to "ranchi haldi", and the
 * shop announced "Showing results for ranchi turmeric" - a brand nobody
 * sells. Aachi is stocked and "ranchi" is two edits away, but nothing knew
 * that: the synonym dictionary holds vocabulary (haldi, chini, doodh), and a
 * brand is not vocabulary. It is catalogue data that changes with the shelf.
 *
 * The interesting half of this test file is the corrections that must NOT
 * happen. A shop that silently rewrites what people ask for is worse than one
 * that occasionally finds nothing, and the first draft of this feature turned
 * "match" - which a kirana shop genuinely sells - into the brand "Catch",
 * one edit away, serving cloves and hing to someone who wanted matchboxes.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class BrandCorrectionTest {

    @Autowired private BrandVocabulary brands;
    @Autowired private SmartSearchService search;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Leaves products_id_seq usable before this test inserts anything.
     *
     * IdentitySequenceDriftTest deliberately winds this sequence BACKWARDS to
     * reproduce the production bug it covers. It repairs it afterwards, but a
     * test that inserts products must not depend on another class's cleanup
     * having run first - surefire's class order is not a contract, and this
     * suite shares one database.
     *
     * FORWARD ONLY, exactly like IdentitySequenceGuard: the GREATEST includes
     * the sequence's own current value, so this can never move a sequence
     * back. An earlier draft compared only against max(id), which on an empty
     * products table would have wound the sequence down to 1 - handing out
     * ids that a later insert has to collide with. That is the failure this
     * exists to prevent, so it must not be the failure this causes.
     */
    @BeforeEach
    void ensureProductSequenceIsUsable() {
        jdbc.queryForObject(
                "SELECT setval('products_id_seq', GREATEST("
                        + "  (SELECT COALESCE(max(id), 0) FROM products), "
                        + "  COALESCE(pg_sequence_last_value('products_id_seq'), 0), "
                        + "  1), true)",
                Long.class);
    }

    /**
     * Every product this class inserts, so it can take them out again.
     *
     * WHY THIS EXISTS. These fixtures used to be left behind, and CI failed
     * three classes later in CatalogSeedIntegrationTest.auditIsClean with
     *
     *     3 test product(s) not attached to a category
     *
     * - the catalogue audit counts the whole products table, so a fixture
     * that outlives its test becomes someone else's failure. It passed
     * locally only because surefire happened to run the audit first.
     */
    private final List<String> insertedProducts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (String name : insertedProducts) {
            jdbc.update("DELETE FROM products WHERE name = ?", name);
        }
        insertedProducts.clear();
        brands.reload();
    }

    private void ensureBrandExists(String brand, String productName) {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE brand = ? AND active", Integer.class, brand);
        if (existing != null && existing > 0) return;
        // is_test_data is FALSE deliberately. These are this class's own
        // fixtures, not rows the catalogue seeder produced, and the pre-launch
        // audit and cleanup both key off that flag - claiming to be seed data
        // would make them the seeder's problem to explain and the cleanup's
        // rows to delete.
        jdbc.update("""
                INSERT INTO products (name, brand, active, bestseller, featured,
                                      is_test_data, price_verified, created_at)
                VALUES (?, ?, true, false, false, false, false, now())
                """, productName, brand);
        insertedProducts.add(productName);
        brands.reload();
    }

    @Test
    @DisplayName("a misheard brand is corrected to the one actually stocked")
    void mishearingIsRepaired() {
        ensureBrandExists("Aachi", "Aachi Turmeric Powder 50 g");
        brands.reload();

        // The exact transcript Google produced, romanised.
        Optional<String> corrected = brands.closestBrand("ranchi");

        assertTrue(corrected.isPresent(),
                "\"ranchi\" is two edits from the stocked brand \"Aachi\"; leaving it alone is what "
                        + "made the shop search for a brand nobody sells");
        assertEquals("Aachi", corrected.get());
    }

    @Test
    @DisplayName("a brand spelled by ear is corrected phonetically")
    void phoneticSpellingIsRepaired() {
        ensureBrandExists("Aashirvaad", "Aashirvaad Atta 5 kg");
        brands.reload();

        // Different failure from a mishearing: a customer typing what they
        // heard. Edit distance alone is a weaker signal here than sound.
        assertEquals(Optional.of("Aashirvaad"), brands.closestBrand("aashirwad"));
    }

    @Test
    @DisplayName("a word that IS the brand is left alone")
    void exactBrandIsNotRewritten() {
        ensureBrandExists("Aachi", "Aachi Turmeric Powder 50 g");
        brands.reload();

        assertEquals(Optional.empty(), brands.closestBrand("aachi"),
                "correcting a brand to itself would report a correction that never happened");
    }

    @Test
    @DisplayName("a short word is never brand-corrected")
    void shortWordsAreLeftAlone() {
        // At three or four characters almost every word is one edit from
        // something, and a correction there is a coin toss dressed up as
        // intelligence.
        assertEquals(Optional.empty(), brands.closestBrand("dal"));
        assertEquals(Optional.empty(), brands.closestBrand("oil"));
    }

    @Test
    @DisplayName("a one-word query is never rewritten to a brand")
    void singleWordQueriesAreNotRewritten() {
        ensureBrandExists("Catch", "Catch Garam Masala 100 g");
        brands.reload();

        // THE FALSE POSITIVE THIS FEATURE CREATED ON ITS FIRST DRAFT.
        // "match" is one edit from the stocked brand "Catch", and a kirana
        // shop sells matchboxes. What separates it from "ranchi haldi" is not
        // the distance - both are a hair from a brand - but that a brand
        // almost never arrives alone. Someone naming a brand names it WITH
        // the thing they want.
        var result = search.search("match", PageRequest.of(0, 5));

        assertNull(result.getInterpretedAs(),
                "a lone word must not be rewritten to a brand; was interpreted as "
                        + result.getInterpretedAs());
    }

    @Test
    @DisplayName("brand plus product word reaches that brand's product end to end")
    void brandPlusProductWordWorksEndToEnd() {
        // DELIBERATELY DISTINCTIVE DATA. The reported case - "ranchi haldi"
        // for Aachi turmeric - cannot be asserted end to end against a shared
        // catalogue that stocks turmeric from five other brands: which of them
        // ranks first is a property of the data, not of this correction, and a
        // test that depends on it would fail whenever someone adds a product.
        //
        // A brand and a product word that nothing else in the shop competes
        // for isolates the mechanism, which is what this test is for. The
        // real-world spelling is covered by mishearingIsRepaired above, and
        // was verified by hand against a production-shaped catalogue:
        // "रांची हल्दी" -> interpreted as "Aachi turmeric", top result
        // "Aachi Turmeric Powder 50 g".
        jdbc.update("DELETE FROM products WHERE brand = 'Zeppelina'");
        jdbc.update("""
                INSERT INTO products (name, brand, active, bestseller, featured,
                                      is_test_data, price_verified, created_at)
                VALUES ('Zeppelina Kumquat Marmalade 200 g', 'Zeppelina',
                        true, false, false, false, false, now())
                """);
        insertedProducts.add("Zeppelina Kumquat Marmalade 200 g");
        brands.reload();

        // One letter wrong, exactly as a recogniser would leave it.
        var result = search.search("zeppelona kumquat", PageRequest.of(0, 5));

        String interpreted = result.getInterpretedAs();
        assertNotNull(interpreted,
                "the customer must be told what was searched instead of what they said");
        assertTrue(interpreted.toLowerCase().contains("zeppelina"),
                "the mishearing must be reported as the real brand, not echoed back; was: " + interpreted);
        assertTrue(result.getResults().hasContent(), "the corrected query must find the product");
        assertEquals("Zeppelina", result.getResults().getContent().get(0).getBrand());

        jdbc.update("DELETE FROM products WHERE brand = 'Zeppelina'");
    }
}
