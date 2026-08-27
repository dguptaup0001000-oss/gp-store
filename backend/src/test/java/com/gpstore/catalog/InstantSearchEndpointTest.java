package com.gpstore.catalog;

import com.gpstore.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/products/search/instant} against the seeded ~1,000-product
 * catalogue. Production used to answer HTTP 500 for every keyword
 * ({@code ANDEXISTS} from a Java text block, then Spring Data wrapping
 * {@code SELECT p.* ... ORDER BY similarity()}). These tests pin the
 * contract: normal input never 500s, limits hold, special characters are
 * parameters not SQL.
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
class InstantSearchEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CatalogSeedService seedService;
    @Autowired private ProductService productService;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    private static boolean seeded;

    @BeforeEach
    void ensureCatalogue() {
        if (!seeded) {
            seedService.seed();
            seeded = true;
        }
        var cache = cacheManager.getCache("productSearch");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("normal search returns 200 with a bounded page")
    void normalSearch() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/search/instant")
                        .param("keyword", "rice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();
        int n = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.content.length()");
        assertTrue(n > 0, "seeded catalog should contain rice");
        assertTrue(n <= 20, "default size is 20, got " + n);
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("\"password\""), body);
    }

    @Test
    @DisplayName("empty keyword is 400, not 500")
    void emptySearch() throws Exception {
        mockMvc.perform(get("/api/products/search/instant").param("keyword", "   "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/products/search/instant"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("LIKE wildcards, quotes, and SQL fragments do not 500")
    void specialCharacters() throws Exception {
        for (String keyword : List.of(
                "%",
                "_",
                "100%",
                "a_b",
                "rice'",
                "rice\"",
                "rice; drop table products",
                "rice--",
                "rice/*",
                "#tag",
                "rice & oil",
                "rice/bran")) {
            mockMvc.perform(get("/api/products/search/instant").param("keyword", keyword))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }
    }

    @Test
    @DisplayName("multiple terms still return 200")
    void multipleSearchTerms() throws Exception {
        mockMvc.perform(get("/api/products/search/instant").param("keyword", "rice bran oil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("a keyword that matches nothing is an empty page, not an error")
    void noResultSearch() throws Exception {
        mockMvc.perform(get("/api/products/search/instant")
                        .param("keyword", "zzzxqwy-no-such-product-9f3a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("page size is capped at 50 even on a ~1000-product catalog")
    void paginationAndLimitOnLargeCatalog() throws Exception {
        mockMvc.perform(get("/api/products/search/instant")
                        .param("keyword", "a")
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()")
                        .value(org.hamcrest.Matchers.lessThanOrEqualTo(5)));

        MvcResult huge = mockMvc.perform(get("/api/products/search/instant")
                        .param("keyword", "a")
                        .param("page", "0")
                        .param("size", "100000"))
                .andExpect(status().isOk())
                .andReturn();
        int n = com.jayway.jsonpath.JsonPath.read(
                huge.getResponse().getContentAsString(), "$.content.length()");
        assertTrue(n <= 50, "size=100000 must be capped at 50, got " + n);

        mockMvc.perform(get("/api/products/search/instant")
                        .param("keyword", "rice")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("concurrent instant searches do not 500")
    void concurrentSearch() throws Exception {
        int workers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<Page<?>>> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            String keyword = (i % 2 == 0) ? "rice" : "milk";
            tasks.add(() -> productService.searchInstant(keyword, PageRequest.of(0, 20)));
        }
        List<Future<Page<?>>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        pool.shutdown();
        for (Future<Page<?>> future : futures) {
            assertFalse(future.isCancelled(), "a search timed out under concurrent load");
            Page<?> page = future.get();
            assertNotNull(page);
            assertTrue(page.getContent().size() <= 20);
        }
    }
}
