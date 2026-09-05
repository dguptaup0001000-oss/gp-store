package com.gpstore.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A listing card must say how many pack sizes a product has.
 *
 * THE BUG THIS ENDS. Every customer-facing list - browse, search, feed,
 * wishlist - goes through ProductResponse.fromCard, which trims a product
 * down to ONE representative variant so a twenty-product page does not
 * serialise a hundred prices no card draws. That trim also removed the only
 * signal the app had that a product comes in 500 g as well as 1 kg. The grid
 * therefore showed one price, and ADD put whichever size the server had
 * picked into the basket. A shopper who wanted the 1 kg bag had no way to
 * know it existed.
 *
 * The fix is four bytes, not the variant list: variantCount rides along with
 * the trimmed card and the app opens a size chooser when it is above one.
 * This test goes over HTTP rather than calling the DTO directly, because the
 * DTO having a getter and the JSON carrying the key are different facts.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class CardKnowsHowManySizesTest {

    private static final String NAME = "zzz three size test atta";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private Long productId;

    /**
     * Forward only, exactly like IdentitySequenceGuard - see
     * CategoryInProductResponseTest for the full reasoning. Surefire's class
     * order is not a contract and this suite shares one database.
     */
    @BeforeEach
    void insertAThreeSizeProduct() {
        jdbc.queryForObject(
                "SELECT setval('products_id_seq', GREATEST("
                        + "  (SELECT COALESCE(max(id), 0) FROM products), "
                        + "  COALESCE(pg_sequence_last_value('products_id_seq'), 0), "
                        + "  1), true)",
                Long.class);

        productId = jdbc.queryForObject(
                "INSERT INTO products (name, brand, active, created_at, updated_at) "
                        + "VALUES (?, 'test brand', true, now(), now()) RETURNING id",
                Long.class, NAME);

        // Three sizes, all sellable, deliberately NOT in price order so the
        // representative variant is chosen rather than stumbled upon.
        insertVariant(0.5, "kg", "40");
        insertVariant(5.0, "kg", "300");
        insertVariant(1.0, "kg", "62");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    @DisplayName("a search card carries one variant and the real number of sizes")
    void searchCardReportsEverySize() throws Exception {
        // param(), not a hand-built query string: MockMvc does not decode "+"
        // as a space, so a keyword written into the URL matched nothing.
        MvcResult result = mockMvc.perform(get("/api/products/search").param("keyword", NAME))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();

        assertTrue(body.contains("\"variantCount\":3"),
                "the card must report all three sizes; was: " + body);
        // Still trimmed - the whole point of fromCard. Three prices would mean
        // the payload saving had been given back.
        assertEquals(1, countOccurrences(body, "\"sellingPrice\""),
                "the card must still carry exactly one variant; was: " + body);
    }

    @Test
    @DisplayName("product detail carries every size and agrees about the count")
    void detailAgreesWithTheCard() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/" + productId)).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();

        assertTrue(body.contains("\"variantCount\":3"),
                "detail must not disagree with the card about how many sizes exist; was: " + body);
        assertEquals(3, countOccurrences(body, "\"sellingPrice\""),
                "detail is the untrimmed response; was: " + body);
    }

    private void insertVariant(double quantity, String unit, String price) {
        jdbc.update(
                "INSERT INTO product_variants (product_id, quantity, unit, selling_price, mrp, "
                        + "available, active) VALUES (?, ?, ?, CAST(? AS numeric), CAST(? AS numeric), true, true)",
                productId, quantity, unit, price, price);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = haystack.indexOf(needle);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(needle, from + needle.length());
        }
        return count;
    }
}
