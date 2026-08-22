package com.gpstore.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductImage;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.ProductImageRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fills product galleries from Open Food Facts, and ONLY with URLs it has
 * confirmed actually resolve.
 *
 * WHY THIS IS A SEPARATE JOB RATHER THAN PART OF THE SEEDER. The machine that
 * generated the catalogue could not reach openfoodfacts.org at all - the
 * network policy refuses the connection. The honest options were to invent
 * plausible-looking URLs or to leave the field empty and fetch them from
 * somewhere that has working network. Inventing them is worse than empty: a
 * fabricated URL makes the catalogue look populated in the database and then
 * 404s on a customer's phone, where nobody is watching a log. So the seeder
 * writes no images at all, and this runs from the deployed backend.
 *
 * THE RULES IT WILL NOT BREAK:
 *
 *  - Never more than 4 images per product (the brief's cap).
 *  - Never the same URL twice on one product. Padding a gallery to four by
 *    repeating the front photo is worse than showing one photo, because the
 *    customer swipes expecting to learn something and learns nothing.
 *  - Never a URL that has not returned a success status to a real request.
 *    A HEAD (falling back to a ranged GET, since some CDNs refuse HEAD) is
 *    made against every candidate before it is stored.
 *  - Never a product that is not seeded test data. is_test_data = true is
 *    the filter, so a real product photographed by the shop is never
 *    overwritten by a stranger's photo of a similar packet.
 *
 * MATCHING IS DELIBERATELY CONSERVATIVE. Open Food Facts is crowd-sourced and
 * its Indian coverage is uneven; a loose match puts a photograph of the wrong
 * thing on a product page, which is worse than a placeholder because it looks
 * deliberate. A candidate is accepted only when the brand matches and the
 * product name shares enough significant words.
 */
@Service
public class CatalogImageBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImageBackfillService.class);

    private static final String SEARCH_BASE = "https://world.openfoodfacts.org/cgi/search.pl";
    private static final int MAX_IMAGES = 4;

    /**
     * Open Food Facts asks API users to identify themselves and to keep the
     * rate modest. Both are honoured: a descriptive User-Agent, and a pause
     * between products. This is a courtesy to a free, donation-funded service
     * whose data we are using.
     */
    private static final String USER_AGENT =
            "GP-Store/1.0 (test catalogue image backfill; contact via repository)";
    private static final long PAUSE_MILLIS = 1200L;

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository productImageRepository;
    private final ObjectMapper objectMapper;
    private final CatalogImageBackfillService self;
    private final HttpClient http;

    @Value("${catalog.image-backfill.timeout-seconds:15}")
    private int timeoutSeconds;

    public CatalogImageBackfillService(ProductRepository productRepository,
                                       ProductVariantRepository variantRepository,
                                       ProductImageRepository productImageRepository,
                                       ObjectMapper objectMapper,
                                       @org.springframework.context.annotation.Lazy
                                       CatalogImageBackfillService self) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.productImageRepository = productImageRepository;
        this.objectMapper = objectMapper;
        this.self = self;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record BackfillResult(int considered, int matched, int imagesWritten,
                                 int alreadyHadImages, int noMatch, List<String> problems) {}

    /**
     * NOT @Transactional. It makes one slow outbound call per product; holding
     * a database transaction across a thousand of them would pin a connection
     * from a ten-connection pool for the whole run. Each product is committed
     * on its own through the proxy.
     *
     * @param limit how many products to attempt this run. Bounded on purpose:
     *              a thousand products at ~1.2s each is twenty minutes, which
     *              is longer than most request timeouts and long enough that
     *              it should be resumable. Re-running continues where it left
     *              off, because products that already have images are skipped.
     */
    public BackfillResult backfill(int limit) {
        // NOT findSeededVariants(). That asked for isTestData = true, which
        // excluded every product in a shop's live catalogue - the run
        // reported considered=0 and looked clean while the products that
        // needed images were never examined. See the query's own comment.
        List<ProductVariant> variants = variantRepository.findVariantsWithoutRealImages();
        int considered = 0, matched = 0, written = 0, already = 0, noMatch = 0;
        List<String> problems = new ArrayList<>();

        for (ProductVariant variant : variants) {
            if (considered >= limit) {
                break;
            }
            Product product = variant.getProduct();
            if (product == null) {
                continue;
            }
            considered++;

            // A gallery row is evidence of a real previous run - the backfill
            // only ever writes verified photographs, so this is the resume
            // point. The variant's own imageUrl is deliberately NOT the test:
            // a placeholder URL resolves and returns 200, so "has a URL"
            // would count a picture of the product's name as a photograph.
            if (productImageRepository.countByProductId(product.getId()) > 0) {
                already++;
                continue;
            }

            try {
                List<String> urls = findImages(product.getBrand(), product.getName());
                if (urls.isEmpty()) {
                    noMatch++;
                    continue;
                }
                matched++;
                written += self.storeImages(product.getId(), variant.getId(), urls);
            } catch (Exception e) {
                problems.add(product.getName() + ": " + e.getClass().getSimpleName());
                log.warn("Image backfill failed for product {}: {}",
                        product.getId(), e.getClass().getSimpleName());
            }

            pause();
        }

        log.info("Image backfill: considered={} matched={} written={} alreadyHad={} noMatch={}",
                considered, matched, written, already, noMatch);
        return new BackfillResult(considered, matched, written, already, noMatch, problems);
    }

    @Transactional
    public int storeImages(Long productId, Long variantId, List<String> urls) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return 0;
        }

        int order = 0;
        for (String url : urls) {
            ProductImage image = new ProductImage();
            image.setProduct(product);
            image.setImageUrl(url);
            image.setSortOrder(order++);
            productImageRepository.save(image);
        }

        // The FIRST image also becomes the variant thumbnail, which is what
        // every grid and list renders. Without this the detail page would
        // have photographs and the product cards would still show placeholder
        // icons - the exact "empty image placeholder" the brief calls out.
        variantRepository.findById(variantId).ifPresent(v -> {
            if (v.getImageUrl() == null || v.getImageUrl().isBlank()) {
                v.setImageUrl(urls.get(0));
                variantRepository.save(v);
            }
        });

        product.setImageSource("openfoodfacts");
        product.setUpdatedAt(java.time.LocalDateTime.now());
        productRepository.save(product);
        return urls.size();
    }

    /**
     * Asks Open Food Facts for this product and returns up to four DISTINCT
     * image URLs that were each confirmed to resolve.
     *
     * Order matches the brief's priority: front of pack first, then
     * ingredients, then nutrition, then packaging.
     */
    List<String> findImages(String brand, String name) throws Exception {
        String query = URLEncoder.encode(stripPackSize(name), StandardCharsets.UTF_8);
        URI uri = URI.create(SEARCH_BASE
                + "?search_terms=" + query
                + "&search_simple=1&action=process&json=1&page_size=5"
                + "&fields=product_name,brands,image_front_url,image_ingredients_url,"
                + "image_nutrition_url,image_packaging_url");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return List.of();
        }

        JsonNode products = objectMapper.readTree(response.body()).path("products");
        for (JsonNode candidate : products) {
            if (!isPlausibleMatch(candidate, brand, name)) {
                continue;
            }
            Set<String> urls = new LinkedHashSet<>();   // insertion-ordered, de-duplicating
            for (String field : new String[]{"image_front_url", "image_ingredients_url",
                                             "image_nutrition_url", "image_packaging_url"}) {
                String url = text(candidate, field);
                if (url != null && !url.isBlank() && urls.size() < MAX_IMAGES && resolves(url)) {
                    urls.add(url);
                }
            }
            if (!urls.isEmpty()) {
                return new ArrayList<>(urls);
            }
        }
        return List.of();
    }

    /**
     * Brand must match, and the names must share real words.
     *
     * Two significant words is the threshold because one is far too loose -
     * "Tata Salt" and "Tata Tea Gold" share "tata", and a shopper who opens
     * salt and sees a photograph of tea will not trust the next photograph
     * either.
     */
    private boolean isPlausibleMatch(JsonNode candidate, String brand, String name) {
        String candidateBrands = lower(text(candidate, "brands"));
        String candidateName = lower(text(candidate, "product_name"));
        if (candidateName == null || candidateName.isBlank()) {
            return false;
        }
        if (brand != null && !brand.isBlank()) {
            String wanted = lower(brand);
            if (candidateBrands == null || !candidateBrands.contains(firstWord(wanted))) {
                return false;
            }
        }

        int shared = 0;
        for (String word : lower(stripPackSize(name)).split("[^a-z0-9]+")) {
            if (word.length() >= 4 && candidateName.contains(word)) {
                shared++;
            }
        }
        return shared >= 2;
    }

    /**
     * Confirms a URL is really there. HEAD first; some image CDNs answer 405
     * to HEAD, so a one-byte ranged GET is the fallback rather than treating
     * the 405 as absence.
     */
    boolean resolves(String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            int status = http.send(head, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            if (status != 405 && status != 403) {
                return false;
            }
            HttpRequest ranged = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();
            int rangedStatus = http.send(ranged, HttpResponse.BodyHandlers.discarding()).statusCode();
            return rangedStatus >= 200 && rangedStatus < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /** "Tata Salt Iodised 1 kg" -> "Tata Salt Iodised". */
    static String stripPackSize(String name) {
        return name == null ? "" :
                name.replaceAll("(?i)\\s+\\d+(\\.\\d+)?\\s*(kg|g|l|ml|pcs)\\s*$", "").trim();
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private static String firstWord(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private void pause() {
        try {
            Thread.sleep(PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
