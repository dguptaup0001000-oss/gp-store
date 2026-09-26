package com.gpstore.marketplace.synthetic;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.config.AppBuildInfo;
import com.gpstore.entity.Category;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantLifecycleService;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.repository.CategoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Explicitly invoked, deterministic seeding and cleanup for the temporary
 * marketplace test batch. Nothing calls this service during ordinary startup.
 */
@Service
public class MarketplaceTestDataSeeder {

    private static final int SQL_BATCH_SIZE = 500;
    private static final String BATCH = MarketplaceTestDataGenerator.BATCH_ID;
    private static final String SHOP_CODE_PATTERN = "^MKT100V1-SHOP-[0-9]{3}$";
    private static final String SKU_PATTERN = "^MKT100V1-[0-9]{3}-[0-9]{3}$";
    private static final String CATEGORY_PREFIX = "Test Marketplace - ";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final AppBuildInfo buildInfo;
    private final PlatformProperties platform;
    private final ShopRepository shopRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantLifecycleService merchantLifecycle;
    private final ShopLifecycleService shopLifecycle;

    public MarketplaceTestDataSeeder(JdbcTemplate jdbc, DataSource dataSource,
                                     AppBuildInfo buildInfo, PlatformProperties platform,
                                     ShopRepository shopRepository,
                                     CategoryRepository categoryRepository,
                                     MerchantLifecycleService merchantLifecycle,
                                     ShopLifecycleService shopLifecycle) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.buildInfo = buildInfo;
        this.platform = platform;
        this.shopRepository = shopRepository;
        this.categoryRepository = categoryRepository;
        this.merchantLifecycle = merchantLifecycle;
        this.shopLifecycle = shopLifecycle;
    }

    public record Result(int shops, int merchants, int products, int variants, int listings,
                         int shopsBelowFiftyListings,
                         int buyOnline, int visitToBuy, int serviceAtShop,
                         int withImages, int withoutImages, boolean alreadyPresent) { }

    public enum Operation { INSPECT, SEED, CLEANUP }

    /** A deliberately explicit command request; no default action is defined. */
    public record Request(Operation operation, String batchId, String expectedCommit) { }

    @Transactional
    public Result execute(Request request) {
        authorize(request);
        acquireBatchLock();
        if (request.operation() == Operation.INSPECT) {
            // Read-only verification. It uses the same batch lock as seed and
            // cleanup so an inspection cannot report a half-written batch.
            return inspectBatch();
        }
        if (request.operation() == Operation.CLEANUP) {
            return cleanupBatch();
        }
        Result existing = inspectBatch();
        if (existing.shops() > 0 || existing.products() > 0 || existing.listings() > 0) {
            if (isComplete(existing)) {
                return new Result(existing.shops(), existing.merchants(), existing.products(), existing.variants(),
                        existing.listings(), existing.shopsBelowFiftyListings(), existing.buyOnline(), existing.visitToBuy(),
                        existing.serviceAtShop(), existing.withImages(), existing.withoutImages(), true);
            }
            throw new IllegalStateException("The named synthetic batch is present but incomplete; "
                    + "no rows were changed. Inspect it and use the exact-batch cleanup command.");
        }

        MarketplaceTestDataGenerator.Dataset dataset =
                MarketplaceTestDataGenerator.generate(MarketplaceTestDataGenerator.DEFAULT_SEED);
        Shop anchor = shopRepository.findByCode(platform.getFirstShopCode())
                .orElseThrow(() -> new IllegalStateException("The configured first shop is missing."));
        if (anchor.getLatitude() == null || anchor.getLongitude() == null) {
            throw new IllegalStateException("The configured first shop has no saved coordinates; "
                    + "refusing to guess a test-marketplace location.");
        }

        Map<String, Long> categories = createCategories(dataset);
        Map<String, Long> shopIds = createShops(dataset.shops(), anchor);
        insertCatalogRows(dataset.listings(), categories);
        insertListingRows(dataset.listings(), shopIds);

        Result result = inspectBatch();
        if (!isComplete(result)) {
            throw new IllegalStateException("Seed verification failed inside the transaction: " + result);
        }
        return result;
    }

    private void authorize(Request request) {
        if (request == null || request.operation() == null
                || !BATCH.equals(request.batchId())) {
            throw new IllegalArgumentException("An explicit operation and exact batch ID are required.");
        }
        if (buildInfo.production()) {
            String confirmation = System.getenv("GPSTORE_TEST_DATA_CONFIRMATION");
            String expectedSha = System.getenv("GPSTORE_TEST_DATA_EXPECTED_SHA");
            if (!BATCH.equals(confirmation)
                    || request.expectedCommit() == null
                    || !request.expectedCommit().matches("[0-9a-f]{40}")
                    || !request.expectedCommit().equals(expectedSha)
                    || !request.expectedCommit().equals(buildInfo.gitCommit())
                    || !request.expectedCommit().equals(buildInfo.binaryGitCommit())) {
                throw new IllegalStateException("Production test-data operation refused: exact batch "
                        + "confirmation and a matching source/binary commit identity are required.");
            }
            if (request.operation() != Operation.INSPECT
                    && !"true".equalsIgnoreCase(System.getenv("GPSTORE_TEST_DATA_ALLOW_PRODUCTION"))) {
                throw new IllegalStateException("Production test-data operation requires a one-shot opt-in.");
            }
            String host = jdbcHost();
            if (!"postgres".equalsIgnoreCase(host)) {
                throw new IllegalStateException("Production one-shot operation requires the existing "
                        + "Hostinger Compose database network; saw host " + host + ".");
            }
            return;
        }

        if (!"true".equalsIgnoreCase(System.getenv("ALLOW_TEST_DATA"))
                && !"true".equalsIgnoreCase(System.getProperty("gpstore.test-data.allow"))) {
            throw new IllegalStateException("Test data generation requires an explicit ALLOW_TEST_DATA opt-in.");
        }
        String host = jdbcHost();
        if (!List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Non-production seed operation is restricted to a loopback database.");
        }
    }

    private String jdbcHost() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (uri.getHost() == null) throw new IllegalStateException("Database URL has no host.");
            return uri.getHost();
        } catch (SQLException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to verify the database destination; refusing seed operation.", ex);
        }
    }

    private void acquireBatchLock() {
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('" + BATCH + "'))");
    }

    private Map<String, Long> createCategories(MarketplaceTestDataGenerator.Dataset dataset) {
        Map<String, Long> ids = new HashMap<>();
        List<String> names = dataset.shops().stream().map(s -> CATEGORY_PREFIX + s.category())
                .distinct().sorted().toList();
        for (String name : names) {
            Category category = categoryRepository.findByNameIgnoreCase(name).orElseGet(() -> {
                Category created = new Category();
                created.setName(name);
                created.setDescription("Synthetic marketplace test category; batch=" + BATCH);
                created.setActive(Boolean.TRUE);
                return categoryRepository.save(created);
            });
            if (!Objects.equals(category.getDescription(), "Synthetic marketplace test category; batch=" + BATCH)) {
                throw new IllegalStateException("Category name collision outside this batch: " + name);
            }
            ids.put(name, category.getId());
        }
        return ids;
    }

    private Map<String, Long> createShops(List<MarketplaceTestDataGenerator.ShopSpec> specs, Shop anchor) {
        Map<String, Long> ids = new HashMap<>();
        double latitude = anchor.getLatitude();
        double longitude = anchor.getLongitude();
        double longitudeDegreesPerKm = 111.0d * Math.max(0.1d,
                Math.cos(Math.toRadians(latitude)));

        for (MarketplaceTestDataGenerator.ShopSpec spec : specs) {
            Merchant merchant = merchantLifecycle.register(spec.merchantName(), spec.shopName(),
                    null, null, null, true);
            merchantLifecycle.transition(merchant.getId(), MerchantStatus.PENDING_REVIEW,
                    BATCH + " synthetic merchant staging");
            merchantLifecycle.transition(merchant.getId(), MerchantStatus.APPROVED,
                    BATCH + " synthetic test approval");
            merchantLifecycle.transition(merchant.getId(), MerchantStatus.ACTIVE,
                    BATCH + " synthetic test activation");

            double shopLatitude = latitude + Math.cos(spec.bearingRadians()) * spec.distanceKm() / 111.0d;
            double shopLongitude = longitude + Math.sin(spec.bearingRadians()) * spec.distanceKm()
                    / longitudeDegreesPerKm;
            Shop shop = shopLifecycle.open(merchant.getId(), spec.shopCode(), spec.shopName(),
                    shopLatitude, shopLongitude, spec.deliveryRadiusKm(), "Asia/Kolkata");
            shop.setBusinessName(spec.shopName());
            shop.setAddressLine("Synthetic test location " + String.format("%03d", spec.ordinal()));
            shop.setLocality(spec.locality());
            shop.setCity(anchor.getCity());
            shop.setState(anchor.getState());
            shop.setPincode(anchor.getPincode());
            shopRepository.save(shop);
            shopLifecycle.transitionAsPlatform(shop.getId(), ShopStatus.ACTIVE,
                    BATCH + " visible for marketplace testing");
            // These are explicit production TEST shops, but BUY_ONLINE still
            // has to exercise the real basket path. A browse-only PAUSED/OFF
            // shop can advertise an ADD button that TenantContextFilter then
            // correctly refuses, which makes the generated marketplace a
            // misleading test fixture. Keep the merchant active and the
            // order switch on; the TEST names and batch markers remain the
            // isolation/cleanup boundary.
            jdbc.update("UPDATE store_operations_settings SET order_acceptance = 'ON', "
                            + "closure_message = NULL, updated_by = ? WHERE shop_id = ?",
                    BATCH, shop.getId());
            ids.put(spec.shopCode(), shop.getId());
        }
        return ids;
    }

    private void insertCatalogRows(List<MarketplaceTestDataGenerator.ListingSpec> specs,
                                   Map<String, Long> categories) {
        List<Object[]> products = new ArrayList<>(specs.size());
        for (MarketplaceTestDataGenerator.ListingSpec item : specs) {
            products.add(new Object[]{item.productName(), item.brand(),
                    categories.get(CATEGORY_PREFIX + item.category()), item.description(),
                    item.subcategory(), item.productName() + " " + item.brand() + " " + item.category(),
                    item.bestseller(), item.featured(), BATCH});
        }
        batch("INSERT INTO products (name, brand, category_id, active, description, subcategory, "
                        + "search_keywords, bestseller, featured, is_test_data, price_verified, "
                        + "is_private_product, data_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?, ?, ?, ?, TRUE, FALSE, FALSE, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                products);

        Map<String, Long> productIds = new HashMap<>(specs.size());
        jdbc.query("SELECT id, name FROM products WHERE is_test_data = TRUE AND data_source = ?",
                rs -> { productIds.put(rs.getString("name"), rs.getLong("id")); }, BATCH);
        if (productIds.size() != specs.size()) {
            throw new IllegalStateException("Product batch identity mismatch.");
        }
        List<Object[]> variants = new ArrayList<>(specs.size());
        for (MarketplaceTestDataGenerator.ListingSpec item : specs) {
            variants.add(new Object[]{productIds.get(item.productName()), item.quantity(), item.unit(),
                    item.sku(), Boolean.TRUE, item.mrp(), item.costPrice(), item.sellingPrice(), Boolean.TRUE});
        }
        batch("INSERT INTO product_variants (product_id, quantity, unit, sku, available, mrp, "
                        + "cost_price, selling_price, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", variants);
    }

    private void insertListingRows(List<MarketplaceTestDataGenerator.ListingSpec> specs,
                                   Map<String, Long> shopIds) {
        Map<String, Long> variantIds = new HashMap<>(specs.size());
        jdbc.query("SELECT v.id, v.sku FROM product_variants v "
                        + "JOIN products p ON p.id = v.product_id "
                        + "WHERE p.is_test_data = TRUE AND p.data_source = ? AND v.sku ~ ?",
                rs -> { variantIds.put(rs.getString("sku"), rs.getLong("id")); },
                BATCH, SKU_PATTERN);
        if (variantIds.size() != specs.size()) {
            throw new IllegalStateException("Variant batch identity mismatch.");
        }

        List<Object[]> listings = new ArrayList<>(specs.size());
        List<Object[]> inventory = new ArrayList<>(specs.size());
        for (MarketplaceTestDataGenerator.ListingSpec item : specs) {
            Long shopId = shopIds.get(item.shopCode());
            Long variantId = variantIds.get(item.sku());
            if (shopId == null || variantId == null) throw new IllegalStateException("Missing generated parent row.");
            listings.add(new Object[]{shopId, variantId, item.sellingPrice(), item.costPrice(), item.mrp(),
                    Boolean.TRUE, Boolean.TRUE, item.shopOrdinal(), item.commerceMode().name(),
                    item.priceMode().name(), item.priceMax(),
                    item.offlineAvailability() == null ? null : item.offlineAvailability().name(),
                    item.serviceDurationMinutes() == 0 ? null : item.serviceDurationMinutes()});
            inventory.add(new Object[]{shopId, variantId, item.stock(), 0, 2, 100});
        }
        batch("INSERT INTO shop_product_variants (shop_id, product_variant_id, selling_price, cost_price, mrp, "
                        + "available, active, display_order, commerce_mode, price_mode, price_max, "
                        + "offline_availability, service_duration_minutes, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                listings);
        batch("INSERT INTO inventory (shop_id, product_variant_id, stock, reserved_stock, minimum_stock, maximum_stock) "
                + "VALUES (?, ?, ?, ?, ?, ?)", inventory);
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += SQL_BATCH_SIZE) {
            int to = Math.min(rows.size(), from + SQL_BATCH_SIZE);
            jdbc.batchUpdate(sql, rows.subList(from, to));
        }
    }

    private Result inspectBatch() {
        int shops = jdbc.queryForObject("SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ ?",
                Integer.class, SHOP_CODE_PATTERN);
        String merchantPrefix = "[" + BATCH + "] Merchant ";
        int merchants = jdbc.queryForObject("SELECT count(*) FROM merchants WHERE is_demo = TRUE "
                        + "AND left(legal_name, length(?)) = ?", Integer.class,
                merchantPrefix, merchantPrefix);
        int products = jdbc.queryForObject("SELECT count(*) FROM products WHERE is_test_data = TRUE AND data_source = ?",
                Integer.class, BATCH);
        int variants = jdbc.queryForObject("SELECT count(*) FROM product_variants v "
                        + "JOIN products p ON p.id = v.product_id "
                        + "WHERE p.is_test_data = TRUE AND p.data_source = ? AND v.sku ~ ?",
                Integer.class, BATCH, SKU_PATTERN);
        int listings = jdbc.queryForObject("SELECT count(*) FROM shop_product_variants spv "
                        + "JOIN product_variants v ON v.id = spv.product_variant_id "
                        + "JOIN products p ON p.id = v.product_id WHERE p.is_test_data = TRUE AND p.data_source = ?",
                Integer.class, BATCH);
        Map<String, Integer> modes = new HashMap<>();
        jdbc.query("SELECT spv.commerce_mode, count(*) AS total FROM shop_product_variants spv "
                        + "JOIN product_variants v ON v.id = spv.product_variant_id "
                        + "JOIN products p ON p.id = v.product_id "
                        + "WHERE p.is_test_data = TRUE AND p.data_source = ? GROUP BY spv.commerce_mode",
                rs -> { modes.put(rs.getString(1), rs.getInt(2)); }, BATCH);
        int withImages = jdbc.queryForObject("SELECT count(DISTINCT p.id) FROM products p "
                        + "JOIN product_variants v ON v.product_id = p.id LEFT JOIN product_images pi ON pi.product_id = p.id "
                        + "WHERE p.is_test_data = TRUE AND p.data_source = ? "
                        + "AND (NULLIF(v.image_url, '') IS NOT NULL OR pi.id IS NOT NULL)",
                Integer.class, BATCH);
        int shopsBelowFifty = jdbc.queryForObject("SELECT count(*) FROM (SELECT s.id "
                        + "FROM shops s LEFT JOIN shop_product_variants spv ON spv.shop_id = s.id "
                        + "LEFT JOIN product_variants v ON v.id = spv.product_variant_id "
                        + "LEFT JOIN products p ON p.id = v.product_id AND p.is_test_data = TRUE AND p.data_source = ? "
                        + "WHERE s.is_demo = TRUE AND s.code ~ ? "
                        + "GROUP BY s.id HAVING count(p.id) < 50) undersized",
                Integer.class, BATCH, SHOP_CODE_PATTERN);
        return new Result(shops, merchants, products, variants, listings, shopsBelowFifty,
                modes.getOrDefault(CommerceMode.ONLINE_PURCHASE.name(), 0),
                modes.getOrDefault(CommerceMode.VISIT_TO_BUY.name(), 0),
                modes.getOrDefault(CommerceMode.SERVICE_AT_SHOP.name(), 0), withImages,
                Math.max(0, products - withImages), false);
    }

    private static boolean isComplete(Result result) {
        return result.shops() == MarketplaceTestDataGenerator.SHOP_COUNT
                && result.merchants() == MarketplaceTestDataGenerator.SHOP_COUNT
                && result.products() >= MarketplaceTestDataGenerator.LISTING_COUNT
                && result.variants() >= MarketplaceTestDataGenerator.LISTING_COUNT
                && result.listings() >= MarketplaceTestDataGenerator.LISTING_COUNT
                && result.shopsBelowFiftyListings() == 0
                && result.buyOnline() > 0 && result.visitToBuy() > 0 && result.serviceAtShop() > 0;
    }

    private Result cleanupBatch() {
        Result before = inspectBatch();
        if (before.shops() == 0 && before.merchants() == 0
                && before.products() == 0 && before.listings() == 0) return before;
        if (!isComplete(before)) {
            throw new IllegalStateException("Cleanup refused: batch identity/counts are incomplete; "
                    + "inspect the exact batch before deleting anything.");
        }

        String shops = "SELECT id FROM shops WHERE is_demo = TRUE AND code ~ '" + SHOP_CODE_PATTERN + "'";
        String products = "SELECT id FROM products WHERE is_test_data = TRUE AND data_source = '" + BATCH + "'";
        String variants = "SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.is_test_data = TRUE AND p.data_source = '" + BATCH + "'";
        assertNoExternalReferences(shops, products, variants);

        long nonBatchListings = jdbc.queryForObject("SELECT count(*) FROM shop_product_variants spv "
                        + "WHERE spv.shop_id IN (" + shops + ") AND NOT EXISTS (SELECT 1 "
                        + "FROM product_variants v JOIN products p ON p.id = v.product_id "
                        + "WHERE v.id = spv.product_variant_id AND p.is_test_data = TRUE AND p.data_source = ?)",
                Long.class, BATCH);
        if (nonBatchListings != 0) {
            throw new IllegalStateException("Cleanup refused: a test shop has non-batch listings.");
        }
        String merchantPrefix = "[" + BATCH + "] Merchant ";
        long merchantShopMismatch = jdbc.queryForObject("SELECT count(*) FROM merchants m WHERE m.is_demo = TRUE "
                        + "AND left(m.legal_name, length(?)) = ? "
                        + "AND (SELECT count(*) FROM shops s WHERE s.merchant_id = m.id) <> 1",
                Long.class, merchantPrefix, merchantPrefix);
        if (merchantShopMismatch != 0) {
            throw new IllegalStateException("Cleanup refused: a marked merchant owns additional shop records.");
        }

        // All deletes are in this one transaction. An unexpected foreign key refuses the
        // operation and rolls every preceding delete back; no cascade touches unrelated data.
        jdbc.update("DELETE FROM inventory WHERE product_variant_id IN (" + variants + ")");
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN (" + variants + ")");
        jdbc.update("DELETE FROM product_variant_attributes WHERE product_variant_id IN (" + variants + ")");
        jdbc.update("DELETE FROM product_variants WHERE id IN (" + variants + ")");
        jdbc.update("DELETE FROM products WHERE is_test_data = TRUE AND data_source = ?", BATCH);
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id IN (" + shops + ")");
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id IN (" + shops + ")");
        jdbc.update("DELETE FROM shops WHERE is_demo = TRUE AND code ~ ?", SHOP_CODE_PATTERN);
        jdbc.update("DELETE FROM merchants WHERE is_demo = TRUE AND left(legal_name, length(?)) = ?",
                merchantPrefix, merchantPrefix);
        for (String category : categoryNames()) {
            jdbc.update("DELETE FROM categories c WHERE c.name = ? AND c.description = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM products p WHERE p.category_id = c.id) "
                            + "AND NOT EXISTS (SELECT 1 FROM customer_preferred_shops ps WHERE ps.category_id = c.id) "
                            + "AND NOT EXISTS (SELECT 1 FROM shop_categories sc WHERE sc.global_category_id = c.id) "
                            + "AND NOT EXISTS (SELECT 1 FROM categories child WHERE child.parent_id = c.id)",
                    category, "Synthetic marketplace test category; batch=" + BATCH);
        }
        return new Result(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    private void assertNoExternalReferences(String shops, String products, String variants) {
        assertNoRows("orders", "shop_id", shops, "existing orders for synthetic shops");
        assertNoRows("shop_ratings", "shop_id", shops, "shop ratings");
        assertNoRows("shop_staff", "shop_id", shops, "shop staff memberships");
        assertNoRows("cart_items", "shop_id", shops, "customer cart items");
        assertNoRows("cart_items", "product_variant_id", variants, "customer cart items");
        assertNoRows("order_items", "product_variant_id", variants, "order history");
        assertNoRows("wishlist", "product_id", products, "customer wishlist items");
        assertNoRows("reviews", "product_id", products, "product reviews");
        assertNoRows("product_images", "product_id", products, "product images");
        long variantImages = jdbc.queryForObject("SELECT count(*) FROM product_variants WHERE id IN ("
                + variants + ") AND NULLIF(image_url, '') IS NOT NULL", Long.class);
        if (variantImages > 0) {
            throw new IllegalStateException("Cleanup refused: a generated variant has an image reference.");
        }
        assertNoRows("customer_preferred_shops", "preferred_shop_id", shops,
                "customer preferred-shop settings");
    }

    private void assertNoRows(String table, String column, String idsSql, String description) {
        if (!tableExists(table) || !columnExists(table, column)) return;
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column
                + " IN (" + idsSql + ")", Long.class);
        if (count != null && count > 0) {
            throw new IllegalStateException("Cleanup refused: " + description + " reference this batch.");
        }
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "public." + table));
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    private List<String> categoryNames() {
        return Arrays.asList("Grocery", "Supermarket", "Mobile and Electronics", "Electronics",
                "Hardware", "Clothing", "Footwear", "Home Appliances", "Furniture", "Kitchenware",
                "Beauty and Personal Care", "Stationery", "Bakery and Food", "Restaurant and Food",
                "Medical Test Supplies", "Automotive Parts", "Motorcycle Parts", "Agriculture Supplies",
                "Electrical Supplies", "Computers and Accessories", "Gifts and Home Decor", "Toys and Kids",
                "Home Services", "Repair Services", "Cleaning Services")
                .stream().map(CATEGORY_PREFIX::concat).toList();
    }
}
