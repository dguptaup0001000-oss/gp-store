package com.gpstore.marketplace;

import com.gpstore.config.AppBuildInfo;
import com.gpstore.platform.MerchantLifecycleService;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.ShopLifecycleService;
import com.gpstore.platform.ShopStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Thirty businesses, one database, and a way to ask whether they can see each
 * other.
 *
 * <h2>Where this can run, and why it cannot run anywhere else</h2>
 *
 * <p>FOUR INDEPENDENT GUARDS, AND THE FIRST IS STRUCTURAL. This class lives in
 * {@code src/test}. It is not compiled into the application jar, so there is no
 * code path - no profile, no flag, no mistake, no startup hook - by which
 * production can execute it. The other three exist because "it is only in the
 * test sources" is the kind of thing that stays true until someone moves a
 * file:
 *
 * <ol>
 *   <li>{@link AppBuildInfo#production()} must be false. This is the same flag
 *       {@code /api/version} reports as {@code environment}, so it is the
 *       application's own opinion of what it is, not a guess from a string.</li>
 *   <li>The database must be reachable on loopback. Production's Postgres is a
 *       container on a VPS; nothing on localhost is it.</li>
 *   <li>{@code ALLOW_TEST_DATA=true} (env) or {@code -Dgpstore.test-data.allow=true}
 *       must be set explicitly. Nothing defaults it on.</li>
 * </ol>
 *
 * <p>ALL FOUR FAIL CLOSED. A guard that cannot determine its answer refuses.
 *
 * <h2>What it builds, and what builds it</h2>
 *
 * <p>The STRUCTURE goes through the real services - {@link MerchantLifecycleService},
 * {@link ShopLifecycleService} - because those are what a test of the
 * architecture is supposed to exercise: they walk the real lifecycle, enforce
 * the real preconditions, and grant the real memberships. Bulk ROWS (products,
 * variants, listings, stock, customers, orders) go in through batched JDBC,
 * because five thousand listings created one JPA call at a time is a test of
 * patience rather than of the marketplace.
 *
 * <p>DETERMINISTIC. One seed, one dataset. A failure that only happens on the
 * 1,847th listing has to be reproducible or it cannot be fixed.
 *
 * <h2>Everything is tagged</h2>
 *
 * <p>Every row carries {@link #TAG} in a name, code or email. Cleanup deletes
 * by tag and by nothing else - never TRUNCATE, never "delete where id >
 * something", never a date range. A seeded row that cannot be picked back out
 * individually is a seeded row that has to stay forever.
 */
@Component
public class MarketplaceTestData {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceTestData.class);

    /** In every generated name, code and email. The whole cleanup depends on it. */
    public static final String TAG = "GPTEST";

    /** Phone numbers live in a block that cannot be a real Indian mobile. */
    private static final String PHONE_PREFIX = "9999";

    private final AppBuildInfo buildInfo;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final MerchantLifecycleService merchants;
    private final ShopLifecycleService shops;

    public MarketplaceTestData(AppBuildInfo buildInfo,
                               DataSource dataSource,
                               JdbcTemplate jdbc,
                               MerchantLifecycleService merchants,
                               ShopLifecycleService shops) {
        this.buildInfo = buildInfo;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.shops = shops;
    }

    /** What got built, so a test can assert counts instead of trusting a log line. */
    public record Marketplace(List<Business> businesses,
                              List<Long> customerIds,
                              int products,
                              int variants,
                              int listings,
                              int workers,
                              int orders,
                              int reviews,
                              int offers) {

        public List<Long> shopIds() {
            return businesses.stream().flatMap(b -> b.shopIds().stream()).toList();
        }

        public Business of(Trade trade) {
            return businesses.stream().filter(b -> b.trade() == trade).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no " + trade));
        }
    }

    public record Business(Trade trade, Long merchantId, Long ownerCustomerId,
                           List<Long> shopIds, List<Long> categoryIds) {
        public long shopId() {
            return shopIds().get(0);
        }
    }

    // ================================================================ guards

    /**
     * Refuses to run anywhere that might be real. Called before anything is
     * written, and again by {@link #cleanUp()} - a cleanup pointed at the wrong
     * database is worse than a seed pointed at the wrong database.
     */
    void refuseUnlessDisposable() {
        if (buildInfo.production()) {
            throw new IllegalStateException(
                    "Refusing to generate test data: this application says it is production "
                            + "(app.production=true). That flag is what /api/version reports, "
                            + "so it is the application's own opinion and not a guess.");
        }

        String url = jdbcUrl();
        if (!isLoopback(url)) {
            throw new IllegalStateException(
                    "Refusing to generate test data against a database that is not on "
                            + "loopback. Production's Postgres is not on localhost, so this "
                            + "guard is what keeps the two apart. Saw: " + hostOf(url));
        }

        if (!allowed()) {
            throw new IllegalStateException(
                    "Refusing to generate test data without an explicit opt-in. Set "
                            + "ALLOW_TEST_DATA=true or -Dgpstore.test-data.allow=true. "
                            + "Nothing turns this on by default, on purpose.");
        }
    }

    private static boolean allowed() {
        return "true".equalsIgnoreCase(System.getenv("ALLOW_TEST_DATA"))
                || "true".equalsIgnoreCase(System.getProperty("gpstore.test-data.allow"));
    }

    private String jdbcUrl() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null || url.isBlank()) {
                // FAIL CLOSED. An unknown location is not a safe one.
                throw new IllegalStateException("The datasource would not say where it points.");
            }
            return url;
        } catch (java.sql.SQLException cannotAsk) {
            throw new IllegalStateException(
                    "Refusing to generate test data: could not establish where this datasource "
                            + "points, so it cannot be shown to be disposable.", cannotAsk);
        }
    }

    private static String hostOf(String jdbcUrl) {
        // jdbc:postgresql://HOST:PORT/db
        int start = jdbcUrl.indexOf("//");
        if (start < 0) {
            return "(no host in the url)";
        }
        String rest = jdbcUrl.substring(start + 2);
        int end = rest.indexOf('/');
        String hostAndPort = end < 0 ? rest : rest.substring(0, end);
        int colon = hostAndPort.indexOf(':');
        return colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
    }

    private static boolean isLoopback(String jdbcUrl) {
        String host = hostOf(jdbcUrl).toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("[::1]");
    }

    // =============================================================== build

    /**
     * Builds the whole marketplace.
     *
     * @param seed the same seed rebuilds the same dataset, row for row.
     */
    public Marketplace create(long seed) {
        refuseUnlessDisposable();
        Random random = new Random(seed);
        long started = System.currentTimeMillis();

        List<Business> businesses = new ArrayList<>();
        int products = 0;
        int variants = 0;
        int listings = 0;
        int offers = 0;

        for (Trade trade : Trade.values()) {
            Business business = openBusiness(trade, random);
            businesses.add(business);

            Counts made = stockTheShelves(business, random);
            products += made.products;
            variants += made.variants;
            listings += made.listings;
            offers += seedOffers(business, trade, random);
        }

        List<Long> customers = seedCustomers(random);
        int workers = seedWorkers(businesses, random);
        int orders = seedOrders(businesses, customers, random);
        int reviews = seedReviews(businesses, customers, random);

        log.info("{} marketplace: {} businesses, {} shops, {} products, {} listings, "
                        + "{} customers, {} workers, {} orders in {} ms",
                TAG, businesses.size(), businesses.stream().mapToInt(b -> b.shopIds().size()).sum(),
                products, listings, customers.size(), workers, orders,
                System.currentTimeMillis() - started);

        return new Marketplace(businesses, customers, products, variants, listings,
                workers, orders, reviews, offers);
    }

    private record Counts(int products, int variants, int listings) {}

    /**
     * One business, and the shops under it.
     *
     * <p>THE SHOP COUNT IS NOT UNIFORM, because the shop switcher only has
     * anything to do when a merchant has more than one. Four of the thirty get
     * 2, 3 and 5 shops so that one-shop, few-shop and many-shop merchants all
     * exist in the same database at the same time.
     */
    private Business openBusiness(Trade trade, Random random) {
        String name = TAG + " " + trade.label + " (seeded)";
        Long merchantId = merchants.register(
                name, name, phone(random), email(trade.name().toLowerCase(Locale.ROOT)),
                null, true).getId();
        merchants.transition(merchantId, MerchantStatus.PENDING_REVIEW, TAG + " seeded");
        merchants.transition(merchantId, MerchantStatus.APPROVED, TAG + " seeded");
        merchants.transition(merchantId, MerchantStatus.ACTIVE, TAG + " seeded");

        long ownerId = account(trade.name().toLowerCase(Locale.ROOT) + "-owner", "ADMIN", random);
        jdbc.update("UPDATE merchants SET owner_customer_id = ? WHERE id = ?", ownerId, merchantId);

        int shopCount = switch (trade) {
            case KIRANA -> 5;
            case PHARMACY -> 3;
            case RESTAURANT -> 2;
            case HARDWARE -> 2;
            default -> 1;
        };

        List<Long> shopIds = new ArrayList<>();
        for (int i = 0; i < shopCount; i++) {
            String code = (TAG + "-" + trade.name() + (i == 0 ? "" : "-" + (i + 1)))
                    .toLowerCase(Locale.ROOT);
            // Spread the pins so distance-based discovery has something to sort.
            double lat = 26.70 + random.nextDouble() * 0.20;
            double lng = 83.30 + random.nextDouble() * 0.20;
            BigDecimal radius = BigDecimal.valueOf(new int[]{1, 5, 10, 20, 40}[random.nextInt(5)]);

            long shopId = shops.open(merchantId, code, TAG + " " + trade.label + (i == 0 ? "" : " #" + (i + 1)),
                    lat, lng, radius, "Asia/Kolkata").getId();
            shops.transitionAsPlatform(shopId, ShopStatus.ACTIVE, TAG + " seeded");
            shopIds.add(shopId);
            seedHours(shopId, trade, random);
            seedDeliveryPricing(shopId, random);
        }

        List<Long> categoryIds = seedCategories(trade);
        return new Business(trade, merchantId, ownerId, shopIds, categoryIds);
    }

    /**
     * The platform taxonomy rows this trade needs.
     *
     * <p>THE CENTRAL TAXONOMY IS SHARED ON PURPOSE - it is the marketplace's
     * own list of what things are, and two shops selling shoes should agree
     * about the word "Shoes". What must NOT be shared is a merchant's own
     * departments and, far more importantly, their listings. That distinction
     * is exactly what {@code CategoriesDoNotLeakTest} goes after.
     */
    private List<Long> seedCategories(Trade trade) {
        List<Long> ids = new ArrayList<>();
        for (String category : trade.categories) {
            String name = TAG + " " + category;
            Long existing = jdbc.query(
                    "SELECT id FROM categories WHERE name = ? LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null, name);
            if (existing != null) {
                ids.add(existing);
                continue;
            }
            jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", name);
            ids.add(jdbc.queryForObject(
                    "SELECT id FROM categories WHERE name = ?", Long.class, name));
        }
        return ids;
    }

    /**
     * Products, variants, listings and stock for one business.
     *
     * <p>THE CENTRAL PRODUCT AND THE SHOP LISTING ARE WRITTEN SEPARATELY, which
     * is the point rather than an implementation detail: a row in
     * {@code products} is the marketplace saying an item exists, and a row in
     * {@code shop_product_variants} is one merchant saying they sell it. Any
     * test that wants to prove a phone shop does not own rice needs those two
     * to have been created independently, and here they are.
     */
    private Counts stockTheShelves(Business business, Random random) {
        Trade trade = business.trade();
        int target = trade.size.min + random.nextInt(Math.max(1, trade.size.max - trade.size.min));

        List<Object[]> productRows = new ArrayList<>();
        for (int i = 0; i < target; i++) {
            String stem = trade.productStems.get(i % trade.productStems.size());
            // THE TRADE IS IN THE NAME, and that is not decoration. Selecting
            // these products back by their CATEGORY looked right until two
            // trades turned out to share a category word - the later business
            // then re-read the earlier one's products and tried to give them a
            // second set of attributes, which V71's unique index refused. The
            // product's own business is the only unambiguous handle.
            String name = TAG + "-" + trade.name() + " " + stem + " " + (i + 1);
            Long categoryId = business.categoryIds().get(i % business.categoryIds().size());
            productRows.add(new Object[]{name, TAG + " brand", categoryId});
        }
        jdbc.batchUpdate(
                "INSERT INTO products (name, brand, category_id, active) VALUES (?, ?, ?, true)",
                productRows);

        List<Long> productIds = jdbc.queryForList(
                "SELECT id FROM products WHERE name LIKE ? ORDER BY id",
                Long.class, TAG + "-" + trade.name() + " %");

        // One or two variants each, so multi-variant products exist without
        // every product being multi-variant.
        List<Object[]> variantRows = new ArrayList<>();
        Map<Long, Integer> variantsPerProduct = new LinkedHashMap<>();
        for (Long productId : productIds) {
            int howMany = random.nextInt(100) < 40 ? 2 : 1;
            variantsPerProduct.put(productId, howMany);
            for (int v = 0; v < howMany; v++) {
                // quantity/unit remain what they always were - they weigh a
                // basket. What tells a phone from a phone is the generic
                // attributes below, not these.
                BigDecimal selling = BigDecimal.valueOf(50 + random.nextInt(30000));
                variantRows.add(new Object[]{productId, 1.0d, "piece", selling,
                        selling.add(BigDecimal.valueOf(random.nextInt(500)))});
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO product_variants "
                        + "(product_id, quantity, unit, selling_price, mrp, active, available) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)", variantRows);

        List<Long> variantIds = jdbc.queryForList(
                "SELECT v.id FROM product_variants v WHERE v.product_id IN ("
                        + placeholders(productIds.size()) + ") ORDER BY v.id",
                Long.class, productIds.toArray());

        // GENERIC ATTRIBUTES (V71). A phone gets RAM and Storage; a saree gets
        // Material and Design. If any of this had to become "pack size", the
        // model would still be grocery-shaped.
        List<Object[]> attributeRows = new ArrayList<>();
        for (Long variantId : variantIds) {
            int order = 0;
            for (String attributeName : trade.attributeNames) {
                attributeRows.add(new Object[]{variantId, attributeName,
                        trade.attributeValues.get(random.nextInt(trade.attributeValues.size())),
                        order++});
            }
        }
        // TIMESTAMPS STATED, NOT LEFT TO A DEFAULT.
        //
        // V71 declares these NOT NULL DEFAULT CURRENT_TIMESTAMP, and omitting
        // them worked locally for exactly that reason - but CI refused the
        // same insert with "null value in column created_at violates not-null
        // constraint". On a fresh database Hibernate's ddl-auto=update creates
        // this table from the entity first (NOT NULL, no DB default), and
        // V71's CREATE TABLE IF NOT EXISTS then silently no-ops, so the
        // default the migration intended is simply not there.
        //
        // The entity never notices because @PrePersist fills both. Raw JDBC
        // has no @PrePersist, so it says so itself rather than depending on
        // which of the two paths happened to create the table.
        jdbc.batchUpdate(
                "INSERT INTO product_variant_attributes "
                        + "(product_variant_id, name, value, display_order, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                attributeRows);

        // The listings. Every shop of this business lists this business's own
        // variants and nothing else.
        int listings = 0;
        for (Long shopId : business.shopIds()) {
            List<Object[]> listingRows = new ArrayList<>();
            List<Object[]> stockRows = new ArrayList<>();
            for (Long variantId : variantIds) {
                BigDecimal selling = BigDecimal.valueOf(50 + random.nextInt(30000));
                listingRows.add(new Object[]{shopId, variantId, selling,
                        selling.add(BigDecimal.valueOf(random.nextInt(500))), true, true});
                // Some shelves are empty on purpose - out of stock is a state
                // the storefront has rules about.
                int stock = random.nextInt(100) < 12 ? 0 : 1 + random.nextInt(50);
                stockRows.add(new Object[]{shopId, variantId, stock});
            }
            jdbc.batchUpdate(
                    "INSERT INTO shop_product_variants "
                            + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                            + " created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    listingRows);
            jdbc.batchUpdate(
                    "INSERT INTO inventory (shop_id, product_variant_id, stock) "
                            + "VALUES (?, ?, ?)", stockRows);
            listings += listingRows.size();
        }

        return new Counts(productIds.size(), variantIds.size(), listings);
    }

    // ------------------------------------------------------------- the rest

    private void seedHours(long shopId, Trade trade, Random random) {
        // A 24-hour biryani place, a 10-6 jeweller, and a 9-9 kirana all exist.
        String open = switch (trade) {
            case BIRYANI, RESTAURANT -> "00:00";
            case JEWELLERY, BOOKS -> "10:00";
            default -> "09:00";
        };
        String close = switch (trade) {
            case BIRYANI, RESTAURANT -> "23:59";
            case JEWELLERY, BOOKS -> "18:00";
            default -> "21:00";
        };
        for (int day = 1; day <= 7; day++) {
            // A WEEKLY HOLIDAY IS THE ABSENCE OF A ROW, which is how this
            // schema says "closed" - there is no closed flag to set.
            boolean holiday = (trade == Trade.JEWELLERY && day == 2)
                    || (trade == Trade.BOOKS && day == 7);
            if (holiday) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO shop_business_hours (shop_id, day_of_week, opens_at, closes_at,
                                                     created_at, updated_at)
                    VALUES (?, ?, CAST(? AS time), CAST(? AS time),
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT DO NOTHING
                    """, shopId, day, open, close);
        }
    }

    private void seedDeliveryPricing(long shopId, Random random) {
        // Per-shop rows already exist (ShopLifecycleService creates them with
        // defaults); vary the numbers so no universal fee can be assumed.
        jdbc.update("UPDATE delivery_pricing_settings SET distance_tier1_charge = ? "
                        + "WHERE shop_id = ?",
                BigDecimal.valueOf(10 + random.nextInt(60)), shopId);
    }

    private int seedOffers(Business business, Trade trade, Random random) {
        // A shop with no offers, a live one, an expired one and a future one -
        // all four states have to exist for "offers do not leak" to mean much.
        if (trade == Trade.JEWELLERY || trade == Trade.GIFTS) {
            return 0;
        }
        long shopId = business.shopId();
        record Offer(String suffix, LocalDate expiry, boolean active) {}
        List<Offer> offers = List.of(
                new Offer("LIVE", LocalDate.now().plusDays(30), true),
                new Offer("GONE", LocalDate.now().minusDays(5), true),
                new Offer("SOON", LocalDate.now().plusDays(90), false));
        for (Offer offer : offers) {
            jdbc.update("""
                    INSERT INTO coupons (shop_id, coupon_code, discount_type, discount_value,
                                         minimum_order_amount, expiry_date, usage_limit,
                                         used_count, active)
                    VALUES (?, ?, 'PERCENTAGE', ?, ?, ?, 100, 0, ?)
                    ON CONFLICT DO NOTHING
                    """, shopId, TAG + "-" + trade.name() + "-" + offer.suffix(),
                    BigDecimal.valueOf(5 + random.nextInt(20)),
                    BigDecimal.valueOf(100), offer.expiry(), offer.active());
        }
        return offers.size();
    }

    /**
     * Customers, most of whom have nothing to do with any particular merchant.
     *
     * <p>THAT IS THE POINT. A merchant must not acquire the marketplace's
     * customer list merely by being a merchant, so the dataset needs a large
     * population that no shop has served.
     */
    private List<Long> seedCustomers(Random random) {
        int howMany = 600;
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            rows.add(new Object[]{TAG + " Customer " + i, email("cust" + i), phone(random)});
        }
        jdbc.batchUpdate("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, rows);
        return jdbc.queryForList(
                "SELECT id FROM customers WHERE email LIKE ? ORDER BY id",
                Long.class, TAG.toLowerCase(Locale.ROOT) + "-cust%");
    }

    /** Uneven staffing, including shops with nobody but the owner. */
    private int seedWorkers(List<Business> businesses, Random random) {
        int made = 0;
        for (Business business : businesses) {
            int howMany = switch (business.trade()) {
                case KIRANA -> 10;
                case RESTAURANT -> 15;
                case BIRYANI -> 3;
                case JEWELLERY, GIFTS -> 0;
                default -> 1;
            };
            for (int i = 0; i < howMany; i++) {
                long workerId = account(
                        business.trade().name().toLowerCase(Locale.ROOT) + "-worker" + i,
                        "DELIVERY_BOY", random);
                jdbc.update("""
                        INSERT INTO shop_staff (shop_id, customer_id, is_default, active)
                        VALUES (?, ?, false, true) ON CONFLICT DO NOTHING
                        """, business.shopId(), workerId);
                made++;
            }
        }
        return made;
    }

    /**
     * Historical orders across the whole marketplace.
     *
     * <p>ONLY STATES THE REAL MACHINE PRODUCES. Writing an order straight into
     * a state the application could never have reached would make every later
     * assertion meaningless - the dataset would be testing itself rather than
     * GP-STORE.
     */
    private int seedOrders(List<Business> businesses, List<Long> customers, Random random) {
        // THE SCHEMA'S OWN VOCABULARY, not an invented one. An earlier draft
        // used "PREPARING" and orders_order_status_check refused it - which is
        // the database declining to hold a state the application could never
        // have produced. DELIVERED is weighted because most orders end there.
        String[] states = {"CONFIRMED", "PACKING", "PACKED", "READY_TO_DISPATCH",
                "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERED", "DELIVERED",
                "CANCELLED", "REJECTED", "DELIVERY_FAILED", "COMPLETED"};
        List<Object[]> rows = new ArrayList<>();
        int target = 2200;
        for (int i = 0; i < target; i++) {
            Business business = businesses.get(random.nextInt(businesses.size()));
            long shopId = business.shopIds().get(random.nextInt(business.shopIds().size()));
            // Most orders belong to a minority of customers - a real shop has
            // regulars - and a large tail of customers has never ordered.
            long customerId = customers.get(random.nextInt(Math.max(1, customers.size() / 3)));
            String state = states[random.nextInt(states.length)];
            String payment = random.nextInt(100) < 60 ? "COD_PENDING" : "SUCCESS";
            rows.add(new Object[]{TAG + "-ORD-" + i, shopId, customerId,
                    BigDecimal.valueOf(50 + random.nextInt(4000)), state, payment});
        }
        jdbc.batchUpdate("""
                INSERT INTO orders (order_number, shop_id, customer_id, total_amount,
                                    order_status, payment_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows);
        return target;
    }

    /**
     * Reviews, each tied to the delivered order that earns it.
     *
     * <p>NOT A CHOICE THIS MAKES - {@code shop_ratings.order_id} is NOT NULL,
     * so the schema itself refuses a review that is not attached to a purchase.
     * An earlier draft inserted a rating with a shop and a customer and no
     * order and was refused, which is the verified-purchase rule being enforced
     * one layer below the application.
     */
    private int seedReviews(List<Business> businesses, List<Long> customers, Random random) {
        List<Object[]> rows = new ArrayList<>();
        for (Business business : businesses) {
            for (Long shopId : business.shopIds()) {
                // One review per delivered order, for a sample of them.
                List<Map<String, Object>> delivered = jdbc.queryForList(
                        "SELECT id, customer_id FROM orders WHERE shop_id = ? "
                                + "AND order_status = 'DELIVERED' AND order_number LIKE ? "
                                + "LIMIT 20", shopId, TAG + "%");
                for (Map<String, Object> order : delivered) {
                    rows.add(new Object[]{shopId, order.get("customer_id"),
                            order.get("id"), 1 + random.nextInt(5), TAG + " seeded review"});
                }
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO shop_ratings (shop_id, customer_id, order_id, rating, comment,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, rows);
        return rows.size();
    }

    // ============================================================= cleanup

    /**
     * Removes exactly what this generator made, and nothing else.
     *
     * <p>BY TAG, IN FOREIGN-KEY ORDER. No TRUNCATE, no date window, no "id
     * greater than". If a row was not created here it is not matched here,
     * which is the only property that makes it safe to run against a database
     * that also holds somebody's hand-made fixtures.
     */
    @Transactional
    public void cleanUp() {
        refuseUnlessDisposable();
        String like = TAG + "%";
        String emailLike = TAG.toLowerCase(Locale.ROOT) + "-%";

        jdbc.update("DELETE FROM shop_ratings WHERE comment LIKE ?", like);
        jdbc.update("DELETE FROM order_alerts_sent WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", like);
        jdbc.update("DELETE FROM order_items WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_number LIKE ?)", like);
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", like);
        jdbc.update("DELETE FROM coupons WHERE coupon_code LIKE ?", like);

        jdbc.update("DELETE FROM inventory WHERE product_variant_id IN "
                + "(SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.name LIKE ?)", like);
        jdbc.update("DELETE FROM shop_product_variants WHERE product_variant_id IN "
                + "(SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.name LIKE ?)", like);
        jdbc.update("DELETE FROM product_variant_attributes WHERE product_variant_id IN "
                + "(SELECT v.id FROM product_variants v JOIN products p ON p.id = v.product_id "
                + "WHERE p.name LIKE ?)", like);
        jdbc.update("DELETE FROM product_variants WHERE product_id IN "
                + "(SELECT id FROM products WHERE name LIKE ?)", like);
        jdbc.update("DELETE FROM products WHERE name LIKE ?", like);
        jdbc.update("DELETE FROM shop_categories WHERE name LIKE ?", like);
        jdbc.update("DELETE FROM categories WHERE name LIKE ?", like);

        jdbc.update("DELETE FROM push_registrations WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE email LIKE ?)", emailLike);
        jdbc.update("DELETE FROM shop_staff WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code LIKE ?)", TAG.toLowerCase(Locale.ROOT) + "-%");
        jdbc.update("DELETE FROM shop_staff WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE email LIKE ?)", emailLike);
        jdbc.update("DELETE FROM shop_business_hours WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code LIKE ?)", TAG.toLowerCase(Locale.ROOT) + "-%");
        jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code LIKE ?)", TAG.toLowerCase(Locale.ROOT) + "-%");
        jdbc.update("DELETE FROM store_operations_settings WHERE shop_id IN "
                + "(SELECT id FROM shops WHERE code LIKE ?)", TAG.toLowerCase(Locale.ROOT) + "-%");
        jdbc.update("DELETE FROM shops WHERE code LIKE ?", TAG.toLowerCase(Locale.ROOT) + "-%");
        jdbc.update("DELETE FROM merchants WHERE legal_name LIKE ?", like);
        jdbc.update("DELETE FROM customers WHERE email LIKE ?", emailLike);
    }

    // ============================================================ small bits

    private long account(String who, String role, Random random) {
        String email = email(who);
        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, true)
                ON CONFLICT (email) DO NOTHING
                """, TAG + " " + who, email, phone(random), role);
        return jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }

    private static String email(String who) {
        return TAG.toLowerCase(Locale.ROOT) + "-" + who + "@example.test";
    }

    /** 9999xxxxxx is not an allocated Indian mobile block. */
    private static String phone(Random random) {
        return PHONE_PREFIX + String.format("%06d", random.nextInt(1_000_000));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] concat(Object first, List<Long> rest) {
        Object[] all = new Object[rest.size() + 1];
        all[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            all[i + 1] = rest.get(i);
        }
        return all;
    }
}
