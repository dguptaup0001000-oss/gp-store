package com.gpstore.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.gpstore.security.WithStaff;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint that actually serves the seeded catalogue, exercised against a
 * database holding the real 982-product seed.
 *
 * WRITTEN BECAUSE OF A WRONG URL. Someone called /api/catalog/products, which
 * has never existed, got a 500, and concluded the catalogue was broken. The
 * real path is /api/products - and nothing here had ever proved it survives a
 * full-size catalogue rather than the handful of rows a unit test creates.
 * That is a fair gap: 982 products with categories and variants is a
 * different serialisation problem from three.
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
class CatalogProductsEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CatalogSeedService seedService;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    private static boolean seeded = false;

    @org.junit.jupiter.api.BeforeEach
    void ensureCatalogue() {
        // Idempotent, so repeated runs update rather than duplicate - which is
        // itself the property CatalogSeedIntegrationTest asserts.
        if (!seeded) {
            seedService.seed();
            seeded = true;
        }
    }

    @Test
    @DisplayName("the real products endpoint returns 200 with a full catalogue behind it")
    void productsEndpointServesTheSeededCatalogue() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products?page=0&size=20"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.isBlank(), "a 200 with an empty body is not a working endpoint");
        assertTrue(body.contains("\"name\""), "the response carries no product names");
    }

    /**
     * The legacy bare-array endpoint is CAPPED, and this pins that.
     *
     * I flagged this as an unbounded public endpoint while diagnosing an
     * unrelated 500, and I was wrong - ProductService.LEGACY_UNPAGINATED_CAP
     * bounds it at 100, deliberately, with a comment saying these three
     * legacy methods "may never run an unbounded findAll() again".
     *
     * The cap matters more now than when it was written. Before the catalogue
     * was seeded this endpoint returned a handful of products; it now has 982
     * to choose from, is permitAll, and is @Cacheable - so without the cap an
     * unauthenticated caller could make the server materialise the entire
     * catalogue, serialise it to JSON, and write it into Redis, on demand.
     *
     * Asserting the ceiling rather than an exact count: the number of active
     * products changes with the catalogue, the ceiling must not.
     */
    @Test
    @DisplayName("GET /api/products honours page/size and caps size at 50")
    void publicProductsEndpointIsPagedAndCapped() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        int count = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.length()");

        assertTrue(count <= 20, "default size is 20, got " + count);
        assertTrue(count > 0, "the seeded catalogue should make this non-empty");

        mockMvc.perform(get("/api/products?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(5)));

        MvcResult huge = mockMvc.perform(get("/api/products?page=0&size=100000"))
                .andExpect(status().isOk())
                .andReturn();
        int hugeCount = com.jayway.jsonpath.JsonPath.read(
                huge.getResponse().getContentAsString(), "$.length()");
        assertTrue(hugeCount <= 50, "size=100000 must be capped at 50, got " + hugeCount);
    }

    @Test
    @DisplayName("the admin bare-array listing is capped too")
    @WithStaff
    void adminLegacyListingIsCapped() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/admin/all"))
                .andExpect(status().isOk())
                .andReturn();

        int count = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.length()");
        assertTrue(count <= 100, "admin listing returned " + count + " products, uncapped");
    }

    @Test
    @DisplayName("the PAGED alternatives the app actually uses are bounded and capped")
    void paginatedEndpointsAreBounded() throws Exception {
        // These are what the Flutter app calls, and they behave correctly.
        mockMvc.perform(get("/api/products/feed?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(20)))
                .andExpect(jsonPath("$.content[0].variants.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(1)));

        mockMvc.perform(get("/api/products/feed?page=0&size=100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(100)));
    }

    @Test
    @DisplayName("browsing the catalogue needs no login - it is a shop window")
    void browsingIsPublic() throws Exception {
        mockMvc.perform(get("/api/products?page=0&size=5")).andExpect(status().isOk());
        var categories = cacheManager.getCache("categories");
        if (categories != null) {
            categories.clear();
        }
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(100)));
    }

    @Test
    @DisplayName("GET /api/products is marked deprecated in favour of /feed")
    void publicProductsEndpointSendsDeprecationHeaders() throws Exception {
        mockMvc.perform(get("/api/products?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("/api/products/feed")));
    }

    @Test
    @WithStaff
    @DisplayName("the ADMIN catalogue audit reports the seeded products")
    void adminAuditSeesTheCatalogue() throws Exception {
        // This is the endpoint that DOES live under /api/admin/catalog, and
        // the one the caller was probably reaching for.
        mockMvc.perform(get("/api/admin/catalog/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testProducts").value(org.hamcrest.Matchers.greaterThan(900)))
                .andExpect(jsonPath("$.totalCategories").value(org.hamcrest.Matchers.greaterThanOrEqualTo(20)));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("a customer cannot read the catalogue audit")
    void customerCannotAudit() throws Exception {
        mockMvc.perform(get("/api/admin/catalog/audit")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unauthenticated caller cannot read the catalogue audit")
    void unauthenticatedCannotAudit() throws Exception {
        mockMvc.perform(get("/api/admin/catalog/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a category with no products returns an empty page, not an error")
    void emptyCategoryIsEmptyNotBroken() throws Exception {
        mockMvc.perform(get("/api/products/category/999999999?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
