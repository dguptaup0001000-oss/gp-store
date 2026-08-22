package com.gpstore.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpstore.catalog.CatalogImageBackfillService.ImageLookup;
import com.gpstore.catalog.CatalogImageBackfillService.LookupOutcome;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every way the outside world can let this down, and what the shop does about
 * each one.
 *
 * <p>WHY A REAL SERVER. These are the paths that cannot be provoked against
 * the real Open Food Facts on demand - you cannot ask it for a 429, and if
 * you could you should not. A JDK HttpServer on a loopback port gives exact,
 * repeatable control of status, body and delay, with no dependency added and
 * no network involved.
 *
 * <p>THE ONE PROPERTY THAT MATTERS MOST is the last test. A lookup that fails
 * must never be the reason a product loses the image it already had. Every
 * other assertion here is about telling the truth in the report; that one is
 * about not destroying data because somebody else's server had a bad minute.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000",
        "catalog.image-backfill.timeout-seconds=2"
})
class CatalogImageLookupTest {

    private static HttpServer server;

    /** What the stub should do on the next request. Set per test. */
    private static volatile int status = 200;
    private static volatile String body = "{\"products\":[]}";
    private static volatile long delayMillis = 0;
    private static final AtomicInteger requests = new AtomicInteger();

    @Autowired private CatalogImageBackfillService service;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            requests.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        // A THREAD POOL, not the default. With a null executor the JDK server
        // runs every handler on its single dispatcher thread, so the timeout
        // test's four-second sleep blocked the NEXT test's request into a
        // spurious timeout - a failure in the harness that looked exactly
        // like a failure in the classification it was testing.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server == null) return;
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService pool) {
            pool.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void pointAtStub(DynamicPropertyRegistry registry) {
        // The port is only known once the server has bound, so this cannot be
        // a static property file.
        registry.add("catalog.image-backfill.search-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/search");
    }

    @BeforeEach
    void resetStub() {
        status = 200;
        body = "{\"products\":[]}";
        delayMillis = 0;
        requests.set(0);
    }

    // ------------------------------------------------------------ the happy path

    @Test
    @DisplayName("200 with a matching product returns FOUND")
    void okWithImage() {
        // image_front_url points back at the stub, so "does it resolve" is
        // answered by the same server rather than by the internet.
        String image = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
        body = """
               {"products":[{"product_name":"Fortune Vanaspati 1L","brands":"Fortune",
                 "image_front_url":"%s"}]}
               """.formatted(image);

        ImageLookup lookup = service.lookupImages("Fortune", "Vanaspati");

        assertThat(lookup.outcome()).isEqualTo(LookupOutcome.FOUND);
        assertThat(lookup.urls()).containsExactly(image);
    }

    @Test
    @DisplayName("200 with no matching product is NO_PRODUCT_FOUND, not a failure")
    void okWithoutMatch() {
        // The source answered perfectly well. This is the ONLY outcome that
        // may honestly be reported as "no photograph found".
        body = "{\"products\":[{\"product_name\":\"Something Else\",\"brands\":\"Other\"}]}";

        assertThat(service.lookupImages("Fortune", "Vanaspati").outcome())
                .isEqualTo(LookupOutcome.NO_PRODUCT_FOUND);
    }

    @Test
    @DisplayName("200 with an empty product list is NO_PRODUCT_FOUND")
    void okWithEmptyList() {
        body = "{\"products\":[]}";

        assertThat(service.lookupImages("Fortune", "Vanaspati").outcome())
                .isEqualTo(LookupOutcome.NO_PRODUCT_FOUND);
    }

    // ------------------------------------------------- the source letting us down

    @Test
    @DisplayName("429 is EXTERNAL_SOURCE_FAILURE and says it was rate-limited")
    void rateLimited() {
        status = 429;
        body = "slow down";

        ImageLookup lookup = service.lookupImages("Fortune", "Vanaspati");

        assertThat(lookup.outcome()).isEqualTo(LookupOutcome.EXTERNAL_SOURCE_FAILURE);
        assertThat(lookup.detail()).contains("rate-limited");
    }

    @Test
    @DisplayName("403 is EXTERNAL_SOURCE_FAILURE and names the refusal")
    void refused() {
        // The real one. Open Food Facts serves anonymous bulk callers an HTML
        // page with a non-200, and this used to be reported as twenty
        // products confidently absent from the database.
        status = 403;
        body = "<html>Page temporarily unavailable</html>";

        ImageLookup lookup = service.lookupImages("Fortune", "Vanaspati");

        assertThat(lookup.outcome()).isEqualTo(LookupOutcome.EXTERNAL_SOURCE_FAILURE);
        assertThat(lookup.detail()).contains("refused");
    }

    @Test
    @DisplayName("500 is EXTERNAL_SOURCE_FAILURE")
    void serverError() {
        status = 500;

        ImageLookup lookup = service.lookupImages("Fortune", "Vanaspati");

        assertThat(lookup.outcome()).isEqualTo(LookupOutcome.EXTERNAL_SOURCE_FAILURE);
        assertThat(lookup.detail()).contains("temporarily unavailable");
    }

    @Test
    @DisplayName("A timeout is EXTERNAL_SOURCE_FAILURE, not an absent product")
    void timesOut() {
        // Timeout is configured to 2s for this class; the stub sleeps longer.
        delayMillis = 4000;

        ImageLookup lookup = service.lookupImages("Fortune", "Vanaspati");

        assertThat(lookup.outcome()).isEqualTo(LookupOutcome.EXTERNAL_SOURCE_FAILURE);
        assertThat(lookup.detail()).contains("timed out");
    }

    @Test
    @DisplayName("A 200 carrying garbage is EXTERNAL_SOURCE_FAILURE")
    void malformedResponse() {
        // A captive portal, an error page served with 200, a truncated body.
        // The status says fine and the content is unusable - which is the
        // source failing, not the product being absent.
        status = 200;
        body = "<html><body>Not JSON at all</body></html>";

        ImageLookup lookup = service.lookupImages("Fortune", "Vanaspati");

        assertThat(lookup.outcome()).isEqualTo(LookupOutcome.EXTERNAL_SOURCE_FAILURE);
        assertThat(lookup.detail()).contains("could not be parsed");
    }

    // ------------------------------------------------------ the data guarantee

    @Test
    @DisplayName("An existing image is NEVER overwritten because the lookup failed")
    void failureNeverDestroysAnExistingImage() {
        String existing = "https://res.cloudinary.com/demo/image/upload/v1/gp/real-photo.jpg";
        ProductVariant variant = newVariantWithImage(existing);

        // Every failure mode in turn - none of them may touch the column.
        for (int failure : new int[]{403, 429, 500, 503}) {
            status = failure;
            service.backfill(5);

            String after = variantRepository.findById(variant.getId()).orElseThrow().getImageUrl();
            assertThat(after)
                    .as("HTTP %d must not cost this product the photograph it already had", failure)
                    .isEqualTo(existing);
        }

        // And a timeout.
        status = 200;
        delayMillis = 4000;
        try {
            service.backfill(5);
            assertThat(variantRepository.findById(variant.getId()).orElseThrow().getImageUrl())
                    .as("a timeout must not cost this product its photograph either")
                    .isEqualTo(existing);
        } finally {
            // Belt and braces alongside @BeforeEach: a slow stub left behind
            // is the kind of state that fails a different test than the one
            // that set it.
            delayMillis = 0;
        }
    }

    @Test
    @DisplayName("A refused run stops rather than collecting more refusals")
    void refusalStopsTheRun() {
        newVariantWithImage(null);
        newVariantWithImage(null);
        newVariantWithImage(null);

        status = 403;
        requests.set(0);

        CatalogImageBackfillService.BackfillResult result = service.backfill(50);

        assertThat(requests.get())
                .as("hammering a service that has just refused us is both pointless and rude")
                .isEqualTo(1);
        assertThat(result.noMatch())
                .as("a refusal must never be counted as a product that was not found")
                .isZero();
        assertThat(result.problems()).isNotEmpty();
        assertThat(result.problems().get(0)).contains("NOT 'no photograph found'");
    }

    private ProductVariant newVariantWithImage(String imageUrl) {
        Category category = new Category();
        category.setName("Lookup " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        Category saved = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Vanaspati");
        product.setBrand("Fortune");
        product.setActive(true);
        product.setCategory(saved);
        Product savedProduct = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(savedProduct);
        variant.setQuantity(1.0);
        variant.setUnit("L");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setCostPrice(new BigDecimal("60"));
        variant.setImageUrl(imageUrl);
        variant.setAvailable(true);
        variant.setActive(true);
        variant.setDisplayOrder(0);
        return variantRepository.save(variant);
    }
}
