package com.gpstore.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.gpstore.security.WithStaff;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A saved product must come back with a REAL category, not the client's stub.
 *
 * THE BUG THIS ENDS, and it was worse than an error. The admin app created
 * products successfully and then showed "Something went wrong. Please try
 * again." on every one of them.
 *
 * The request body carries only the category id, so Jackson builds a Category
 * with that id and NULL everywhere else. Hibernate needs nothing more - it
 * writes category_id and the row is right - but the response mapped that same
 * stub straight back out:
 *
 *     "category":{"id":1,"name":null,...}
 *
 * Flutter's Category model declares `required String name`, so
 * Category.fromJson threw on the null before the screen ever saw a product.
 * The catch showed the only thing it had, the admin retried, and the retry
 * created a SECOND product. The live catalogue ended up holding two "machar
 * bati" rows and two spellings of "pooja bati" because of it.
 *
 * A 200 that reads as a failure is worse than a failure: it invites the
 * duplicate.
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
class CategoryInProductResponseTest {

    @Autowired private MockMvc mockMvc;
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

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM products WHERE name IN ('machar bati','ghost product')");
    }

    private Long anyCategoryId() {
        Long id = jdbc.queryForObject("SELECT min(id) FROM categories", Long.class);
        assertNotNull(id, "test database has no categories");
        return id;
    }

    @Test
    @WithStaff
    @DisplayName("create returns the category's real name, so the client can parse it")
    void createReturnsAResolvedCategory() throws Exception {
        Long categoryId = anyCategoryId();
        String expectedName = jdbc.queryForObject(
                "SELECT name FROM categories WHERE id = ?", String.class, categoryId);

        MvcResult result = mockMvc.perform(post("/api/products")
                .contentType("application/json")
                .content("""
                         {"name":"machar bati","brand":"dragon","category":{"id":%d},"active":true}
                         """.formatted(categoryId))).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();

        assertFalse(body.contains("\"name\":null"),
                "a null category name breaks Flutter's `required String name` and the admin sees "
                        + "\"Something went wrong\" on a product that was created. Body: " + body);
        assertTrue(body.contains("\"name\":\"" + expectedName + "\""),
                "the response must carry the real category name; was: " + body);
    }

    @Test
    @WithStaff
    @DisplayName("update returns a resolved category too - Save Changes hit the same bug")
    void updateReturnsAResolvedCategory() throws Exception {
        Long categoryId = anyCategoryId();
        MvcResult created = mockMvc.perform(post("/api/products")
                .contentType("application/json")
                .content("""
                         {"name":"machar bati","brand":"dragon","category":{"id":%d},"active":true}
                         """.formatted(categoryId))).andReturn();
        Long productId = Long.valueOf(created.getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":(\\d+).*$", "$1"));

        MvcResult updated = mockMvc.perform(put("/api/products/" + productId)
                .contentType("application/json")
                .content("""
                         {"name":"machar bati","brand":"dragon","category":{"id":%d},"active":true}
                         """.formatted(categoryId))).andReturn();

        assertEquals(200, updated.getResponse().getStatus());
        assertFalse(updated.getResponse().getContentAsString().contains("\"name\":null"),
                "Save Changes must not return a stub category either; was: "
                        + updated.getResponse().getContentAsString());
    }

    @Test
    @WithStaff
    @DisplayName("a category that does not exist is a plain 404, not a database error")
    void unknownCategoryIsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/products")
                .contentType("application/json")
                .content("""
                         {"name":"ghost product","category":{"id":99999999},"active":true}
                         """)).andReturn();

        // Before the category was resolved, this reached the database and came
        // back as a foreign-key violation - a constraint message for what is
        // simply a wrong id.
        assertEquals(404, result.getResponse().getStatus(),
                "body: " + result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("99999999"),
                "the message should name the id that was not found");
    }
}
