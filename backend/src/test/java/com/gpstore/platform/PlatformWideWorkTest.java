package com.gpstore.platform;

import com.gpstore.repository.OrderRepository;
import com.gpstore.service.AnalyticsService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two places that legitimately cross shops, and the one report that must not.
 *
 * WHY THESE ARE TESTED TOGETHER. They are the same question asked from both
 * ends: which work is allowed to see every shop, and which work only looked
 * like it was. A webhook genuinely spans shops - Cashfree does not know which
 * merchant a payment belongs to and neither does the signature. A revenue
 * chart genuinely does not: it renders on one shopkeeper's dashboard, and a
 * total that quietly included the market's takings would be wrong in the most
 * embarrassing possible direction.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("What spans shops does so deliberately, and nothing else does")
class PlatformWideWorkTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orders;
    @Autowired private AnalyticsService analytics;
    @Autowired private TenantContextFilter tenantFilter;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;

    private final String tag = "pw" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private long orderAId;
    private long orderBId;

    private static final BigDecimal SHOP_A_TAKINGS = new BigDecimal("111.00");
    private static final BigDecimal SHOP_B_TAKINGS = new BigDecimal("222.00");

    @BeforeEach
    void twoShopsTradingToday() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Platform-wide fixture " + tag);
        second.setDisplayName("Fixture B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("PW-" + tag);
        b.setDisplayName("Fixture shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "Platform fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        orderAId = insertOrder("PWA-" + tag, shopA, SHOP_A_TAKINGS);
        orderBId = insertOrder("PWB-" + tag, shopB, SHOP_B_TAKINGS);
    }

    @AfterEach
    void removeTheFixture() {
        jdbc.update("DELETE FROM orders WHERE id in (?, ?)", orderAId, orderBId);
        if (customerId != null) {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------------ the report that must not

    @Test
    @DisplayName("the revenue chart totals one shop's takings, not the market's")
    void theNativeRevenueQueryIsScoped() {
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now().plusDays(1);

        BigDecimal forA = totalOf(orders.revenueByDayBetween(from, to, shopA));
        BigDecimal forB = totalOf(orders.revenueByDayBetween(from, to, shopB));
        BigDecimal everything = totalOf(orders.revenueByDayBetween(from, to, null));

        assertEquals(0, SHOP_B_TAKINGS.compareTo(forB),
                "Shop B is a brand-new shop with exactly one order, so its total is that order "
                        + "and nothing else - if it is larger, the query is counting another "
                        + "shop's takings into this shop's dashboard");

        // Checked against a plain SUM rather than against the other results.
        // The test database carries orders from every other test in the suite,
        // and some of those are raw inserts with no shop at all - so comparing
        // the three totals to each other says more about the fixture than
        // about the query. What has to be true is that each shop's number IS
        // that shop's number.
        assertEquals(0, sumForShop(shopA).compareTo(forA),
                "Shop A's chart total must equal the sum of Shop A's own orders");
        assertEquals(0, sumForShop(shopB).compareTo(forB),
                "Shop B's chart total must equal the sum of Shop B's own orders");
        assertTrue(everything.compareTo(forA.max(forB)) >= 0,
                "the platform-wide total is the whole market, so it cannot be smaller than any "
                        + "single shop's");
        assertTrue(forA.compareTo(SHOP_B_TAKINGS.add(sumForShop(shopA))) < 0,
                "Shop A's total must not have Shop B's takings added to it");
    }

    @Test
    @DisplayName("the chart takes its shop from the scope, never from a caller")
    void theChartReadsTheScopeNotAParameter() {
        // AnalyticsService.getSalesSeries has no shop parameter to pass, and
        // that is the design: a dashboard endpoint that accepted one would let
        // a shopkeeper ask for a competitor's numbers by editing a query
        // string.
        assertEquals(0,
                java.util.Arrays.stream(AnalyticsService.class.getMethods())
                        .filter(m -> m.getName().equals("getSalesSeries"))
                        .filter(m -> java.util.Arrays.stream(m.getParameterTypes())
                                .anyMatch(t -> t == Long.class || t == long.class))
                        .count(),
                "getSalesSeries must not take a shop id - the scope is the only source");

        BigDecimal seenByB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> totalOfSeries(analytics.getSalesSeries(2)));

        assertEquals(0, SHOP_B_TAKINGS.compareTo(seenByB),
                "inside Shop B's scope the dashboard must show Shop B's takings and nobody else's");
    }

    // ------------------------------------------- the work that legitimately does

    @Test
    @DisplayName("a payment webhook runs platform-wide, and says so")
    void webhooksGetAnExplicitPlatformScope() throws Exception {
        assertEquals(TenantScope.platform(), scopeSeenBy("/api/payments/webhooks/cashfree"),
                "a webhook arrives from the gateway with a signature and no session; it must be "
                        + "able to find the payment it is about whichever shop sold it");
    }

    @Test
    @DisplayName("auth runs platform-wide too - there is no shop before there is a credential")
    void authGetsAnExplicitPlatformScope() throws Exception {
        assertEquals(TenantScope.platform(), scopeSeenBy("/api/auth/login"));
    }

    @Test
    @DisplayName("an ordinary request still resolves to a shop")
    void everythingElseIsScopedToAShop() throws Exception {
        TenantScope scope = scopeSeenBy("/api/products");

        assertNotNull(scope, "a normal request with no scope would read every shop's rows");
        assertTrue(scope.isSingleShop(),
                "under SINGLE_SHOP every ordinary request resolves to Shop #1, which is what "
                        + "keeps the filter switched on for the shop that is actually trading");
        assertEquals(shopA, scope.requireShopId());
    }

    @Test
    @DisplayName("the scope is taken off the thread whichever branch ran")
    void nothingIsLeftBehindOnTheThread() throws Exception {
        scopeSeenBy("/api/payments/webhooks/cashfree");
        assertFalse(TenantContext.isSet(),
                "a scope left on a Tomcat thread is the next request's scope - which is a "
                        + "cross-shop leak arriving by accident");

        scopeSeenBy("/api/products");
        assertFalse(TenantContext.isSet());
    }

    // ------------------------------------------------------------- fixtures

    /** Runs the real filter over a request for that path and reports the scope it established. */
    private TenantScope scopeSeenBy(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<TenantScope> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.current());

        tenantFilter.doFilter(request, response, chain);
        return seen.get();
    }

    /** What that shop actually took in the window, straight from SQL. */
    private BigDecimal sumForShop(long shopId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT coalesce(sum(total_amount), 0) FROM orders "
                        + "WHERE shop_id = ? AND order_status <> 'CANCELLED' "
                        + "AND order_date >= now() - interval '1 day' "
                        + "AND order_date <= now() + interval '1 day'",
                BigDecimal.class, shopId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private long insertOrder(String number, long shopId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id)
                VALUES (?, ?, ?, 'CONFIRMED', 'COD_PENDING', now(), ?)
                """, customerId, number, amount, shopId);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, number);
    }

    private static BigDecimal totalOf(List<Object[]> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : rows) {
            total = total.add(row[1] instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(row[1])));
        }
        return total;
    }

    private static BigDecimal totalOfSeries(List<Map<String, Object>> series) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> point : series) {
            Object revenue = point.get("revenue");
            if (revenue != null) {
                total = total.add(revenue instanceof BigDecimal b
                        ? b : new BigDecimal(String.valueOf(revenue)));
            }
        }
        return total;
    }
}
