package com.gpstore.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which Open Food Facts result counts as a photograph OF THIS PRODUCT.
 *
 * <p>Plain unit tests, no Spring and no network: the rule reads nothing but
 * its arguments, and asserting it through a live search would make these
 * slow and dependent on somebody else's catalogue changing.
 *
 * <p>THE BUG THESE EXIST FOR. The rule demanded two significant words from
 * the product NAME, written on the assumption that names contain their brand
 * - "Tata Salt". GP-Store keeps brand in its own column, so a name is a short
 * descriptor: "Vanaspati", "Cow Ghee", "Milk". One significant word, or none.
 * The threshold was therefore unreachable for a large part of the catalogue,
 * and a real backfill run over twenty products matched exactly zero while
 * looking like Open Food Facts simply having no Indian groceries.
 */
class CatalogImageMatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode candidate(String brands, String productName) {
        return MAPPER.createObjectNode()
                .put("brands", brands)
                .put("product_name", productName);
    }

    @Test
    @DisplayName("A single-word product name matches when the brand confirms it")
    void singleWordNameMatches() {
        // The regression. "Vanaspati" carries one significant word, so under
        // the old two-word threshold this product could never be matched
        // whatever Open Food Facts returned.
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate("Fortune", "Fortune Vanaspati 1L"), "Fortune", "Vanaspati"))
                .isTrue();
    }

    @Test
    @DisplayName("A name whose only long word is short-listed still matches on brand")
    void shortWordNameMatches() {
        // "Cow Ghee" - "cow" is three letters and never counted, leaving one.
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate("Amul", "Amul Cow Ghee"), "Amul", "Cow Ghee"))
                .isTrue();
    }

    @Test
    @DisplayName("The false positive the old rule protected against is STILL rejected")
    void brandSharedWordIsNotEvidence() {
        // The original comment's example, and the thing a looser threshold
        // could easily have reintroduced: a shopper opening salt and seeing a
        // photograph of tea. "tata" matches only because it is the brand, and
        // the brand is already checked separately - counting it again was
        // double-counting one piece of evidence.
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate("Tata", "Tata Tea Gold"), "Tata", "Salt"))
                .isFalse();
    }

    @Test
    @DisplayName("A different brand is rejected however well the name reads")
    void wrongBrandRejected() {
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate("Patanjali", "Patanjali Vanaspati"), "Fortune", "Vanaspati"))
                .isFalse();
    }

    @Test
    @DisplayName("With no brand to lean on, one word is still not enough")
    void withoutBrandTwoWordsRequired() {
        // Nothing has confirmed this is the right maker, so the name has to
        // carry the whole burden.
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate(null, "Vanaspati Refined"), null, "Vanaspati"))
                .isFalse();
    }

    @Test
    @DisplayName("With no brand, two matching words are accepted")
    void withoutBrandTwoWordsAccepted() {
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate(null, "Rice Bran Oil Refined"), null, "Rice Bran Oil"))
                .isTrue();
    }

    @Test
    @DisplayName("A candidate with no name at all is rejected")
    void namelessCandidateRejected() {
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate("Fortune", ""), "Fortune", "Vanaspati"))
                .isFalse();
    }

    @Test
    @DisplayName("A pack size in the name is not a matching word")
    void packSizeIsStripped() {
        // "Vanaspati 1 L" must not match on the "1 L" - stripPackSize exists
        // so a size never stands in for the product.
        assertThat(CatalogImageBackfillService.isPlausibleMatch(
                candidate("Fortune", "Fortune Refined Palmolein"), "Fortune", "Vanaspati 1 L"))
                .isFalse();
    }
}
