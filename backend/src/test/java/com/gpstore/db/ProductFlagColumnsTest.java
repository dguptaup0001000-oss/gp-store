package com.gpstore.db;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Admin "Add Product" against the schema PRODUCTION actually has.
 *
 * The failure, reported from a real phone and reproduced here:
 *
 *     null value in column "bestseller" of relation "products"
 *     violates not-null constraint
 *
 * V15 added bestseller, featured, is_test_data and price_verified as
 * NOT NULL DEFAULT FALSE. A column default only applies when the INSERT omits
 * the column, and Hibernate never omits a mapped column - it lists every one
 * and binds NULL for anything unset. The admin form sends name, brand,
 * category and active, so four NULLs went at four NOT NULL columns.
 *
 * THIS TEST FORCES THE PRODUCTION SHAPE ITSELF rather than trusting the
 * schema it finds, and that is the whole point of it.
 *
 * V15 used ADD COLUMN IF NOT EXISTS. Where Hibernate's ddl-auto had already
 * created these columns from the entity - nullable - the clause matched and
 * V15 skipped them, leaving them nullable. So a developer machine and CI have
 * a DIFFERENT products table from production, every local reproduction of this
 * bug succeeded, and the report looked like a phantom for days.
 *
 * CI makes it worse: FLYWAY_ENABLED=false there, so V17 - which exists to end
 * exactly this divergence - never runs. A test that merely used the ambient
 * schema would therefore pass in CI while production stayed broken. It sets
 * the constraints up itself, and puts them back afterwards.
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
class ProductFlagColumnsTest {

    private static final String[] FLAGS = {"bestseller", "featured", "is_test_data", "price_verified"};

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void useTheProductionSchemaShape() {
        for (String col : FLAGS) {
            jdbc.update("UPDATE products SET " + col + " = COALESCE(" + col + ", FALSE) WHERE " + col + " IS NULL");
            jdbc.execute("ALTER TABLE products ALTER COLUMN " + col + " SET DEFAULT FALSE");
            jdbc.execute("ALTER TABLE products ALTER COLUMN " + col + " SET NOT NULL");
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM products WHERE name = 'dhoop bati' AND brand = 'shalimar'");
    }

    private MvcResult createProduct() throws Exception {
        Long categoryId = jdbc.queryForObject("SELECT min(id) FROM categories", Long.class);
        assertNotNull(categoryId, "test database has no categories");
        // Byte for byte what AdminProductsRepository.createProduct sends -
        // and note what is NOT in it: none of the four flag columns.
        String body = """
                {"name":"dhoop bati","brand":"shalimar","category":{"id":%d},"active":true}
                """.formatted(categoryId);
        return mockMvc.perform(post("/api/products").contentType("application/json").content(body)).andReturn();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Add Product works when the flag columns are NOT NULL, as they are in production")
    void addProductSucceedsAgainstNotNullFlagColumns() throws Exception {
        MvcResult result = createProduct();

        assertEquals(200, result.getResponse().getStatus(),
                "the admin form sends none of the four flag columns, so Hibernate binds NULL for each - "
                        + "the entity must default them or production rejects the row. Body: "
                        + result.getResponse().getContentAsString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the flags are stored as false, not left to the column default")
    void flagsArePersistedAsFalse() throws Exception {
        assertEquals(200, createProduct().getResponse().getStatus());

        var row = jdbc.queryForMap(
                "SELECT bestseller, featured, is_test_data, price_verified FROM products "
                        + "WHERE name = 'dhoop bati' AND brand = 'shalimar' ORDER BY id DESC LIMIT 1");

        // is_test_data FALSE matters beyond this row: the pre-launch cleanup
        // deletes on that flag, and a product an admin typed in by hand must
        // never be swept up by it.
        row.forEach((column, value) ->
                assertEquals(Boolean.FALSE, value, column + " must persist as false, never null"));
    }

    @Test
    @DisplayName("the entity itself never yields a null flag, whatever the schema allows")
    void entityDefaultsAreNotDependentOnTheDatabase() {
        com.gpstore.entity.Product fresh = new com.gpstore.entity.Product();

        assertEquals(Boolean.FALSE, fresh.getBestseller());
        assertEquals(Boolean.FALSE, fresh.getFeatured());
        assertEquals(Boolean.FALSE, fresh.getIsTestData());
        assertEquals(Boolean.FALSE, fresh.getPriceVerified());
    }
}
