package com.gpstore.platform;

import com.gpstore.entity.Role;
import com.gpstore.money.ShopEarnings;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.RefundRepository;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.RolePermissions;
import com.gpstore.service.AnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A number on a shopkeeper's screen must be about their shop and no other.
 *
 * THIS IS WHERE THE FILTER RAN OUT, and the failure was not the one anyone was
 * watching for. Every isolation test before this one asks whether a shop can
 * READ another shop's rows, and the answer has been no since Slice 2. This one
 * asks a different question - whether a shop's own REPORT is about its own
 * shop - and the answer was no, for a reason that is invisible in the source:
 *
 *   Hibernate's @Filter restricts the entity a query is ROOTED on. It does not
 *   follow a join.
 *
 * OrderItem is not shop-owned. Refund is not shop-owned. Both reach a
 * shop-owned entity through an association, and both were being read as if
 * that association carried the filter with it. It does not. Measured before
 * the fix: 3 units sold in Shop A and 9 in Shop B, and BOTH shops' top-product
 * leaderboards reported 12. The refund sum was worse - it is SUBTRACTED from a
 * shop's revenue, so a brand-new kirana's first dashboard showed the
 * marketplace's refunds against its own takings.
 *
 * So every assertion here has a positive half and a negative half: the shop
 * still sees its own figure, exactly, and does not see the other's added to
 * it. A test that only checked "A cannot see B's row" would have passed
 * through the entire bug.
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
@DisplayName("A shop's own numbers are its own")
class ShopReportingIsolationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private RefundRepository refunds;
    @Autowired private ShopEarnings earnings;
    @Autowired private AnalyticsService analytics;

    private final String tag = "rpt" + System.nanoTime();

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;
    private Long variantId;
    private Long productId;
    private long orderAId;
    private long orderBId;
    private Long paymentAId;
    private Long paymentBId;

    /** Units and money chosen so no two figures can be confused for each other. */
    private static final int UNITS_A = 3;
    private static final int UNITS_B = 9;
    private static final BigDecimal SALE_A = new BigDecimal("500.00");
    private static final BigDecimal SALE_B = new BigDecimal("700.00");
    private static final BigDecimal REFUND_A = new BigDecimal("125.00");
    private static final BigDecimal REFUND_B = new BigDecimal("250.00");

    @BeforeEach
    void twoShopsThatBothTraded() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Reporting fixture merchant " + tag);
        second.setDisplayName("Reporting B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("RPT-" + tag);
        b.setDisplayName("Reporting shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """, "Reporting fixture " + tag, tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        // One product sold by BOTH shops - the shape that makes a leak visible.
        // A leak between shops selling different things hides in the totals.
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants ORDER BY id LIMIT 1", Long.class);
        productId = jdbc.queryForObject(
                "SELECT product_id FROM product_variants WHERE id = ?", Long.class, variantId);

        orderAId = insertOrder("RPTA-" + tag, shopA, SALE_A);
        orderBId = insertOrder("RPTB-" + tag, shopB, SALE_B);
        insertLine(orderAId, UNITS_A, SALE_A);
        insertLine(orderBId, UNITS_B, SALE_B);
        paymentAId = insertPayment(orderAId, shopA, SALE_A);
        paymentBId = insertPayment(orderBId, shopB, SALE_B);
        insertRefund(paymentAId, REFUND_A, "rfa-" + tag);
        insertRefund(paymentBId, REFUND_B, "rfb-" + tag);
    }

    @AfterEach
    void tidyUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM refunds WHERE payment_id in (?, ?)", paymentAId, paymentBId);
        jdbc.update("DELETE FROM payments WHERE id in (?, ?)", paymentAId, paymentBId);
        jdbc.update("DELETE FROM order_items WHERE order_id in (?, ?)", orderAId, orderBId);
        jdbc.update("DELETE FROM orders WHERE id in (?, ?)", orderAId, orderBId);
        if (customerId != null) {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------- the queries that had leaked

    @Test
    @DisplayName("a shop's top products count its own units, not the marketplace's")
    void topProductsAreCountedPerShop() {
        long inA = unitsForProbeProduct(shopA);
        long inB = unitsForProbeProduct(shopB);

        assertEquals(UNITS_A, inA,
                "Shop A's leaderboard must count the " + UNITS_A + " it sold. " + inA
                        + " means it is counting Shop B's " + UNITS_B + " as well - which is what "
                        + "it did before this slice, because @Filter does not follow a join");
        assertEquals(UNITS_B, inB, "Shop B's leaderboard is counting units it did not sell");
    }

    @Test
    @DisplayName("a new shop's refunds are its own, so its net revenue is not somebody else's loss")
    void refundsAreSummedPerShop() {
        BigDecimal inB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> refunds.settledForOrdersBetween(dayAgo(), soon(),
                        TenantContext.reportingShopId()));

        assertEquals(0, REFUND_B.compareTo(inB),
                "Shop B refunded " + REFUND_B + " and this says " + inB + ". A refund total that "
                        + "includes other shops is subtracted from THIS shop's revenue, so a new "
                        + "kirana's first dashboard reports a loss it never made");
    }

    @Test
    @DisplayName("the dashboard's net revenue for one shop is that shop's takings minus its own refunds")
    void netRevenueIsOneShopsArithmetic() {
        Map<String, Object> summaryB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> analytics.getSalesSummary(2));

        BigDecimal revenue = (BigDecimal) summaryB.get("revenue");
        BigDecimal refunded = (BigDecimal) summaryB.get("refunded");
        BigDecimal net = (BigDecimal) summaryB.get("netRevenue");

        assertEquals(0, SALE_B.compareTo(revenue), "Shop B's revenue is not its own sale");
        assertEquals(0, REFUND_B.compareTo(refunded), "Shop B's refunds are not its own");
        assertEquals(0, SALE_B.subtract(REFUND_B).compareTo(net),
                "net revenue must be this shop's takings less this shop's refunds");
        assertTrue(net.signum() > 0,
                "a shop that sold " + SALE_B + " and refunded " + REFUND_B + " is not in the red; "
                        + "a negative here is the marketplace's refunds landing on one shop");
    }

    @Test
    @DisplayName("trending is what sells in THIS shop, not next door")
    void trendingIsPerShop() {
        long inA = countForProbeProduct(shopA);
        long inB = countForProbeProduct(shopB);

        assertEquals(1L, inA, "Shop A has one order line for the probe product");
        assertEquals(1L, inB, "Shop B has one order line for the probe product");
    }

    // ---------------------------------------------------- the money statement

    @Test
    @DisplayName("a shop's earnings statement is its own sales, refunds and collections")
    void earningsAreOneShopsMoney() {
        ShopEarnings.Statement b = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> earnings.forCurrentShop(2));

        assertEquals(0, SALE_B.compareTo(b.grossSales()), "gross sales are not Shop B's");
        assertEquals(0, REFUND_B.compareTo(b.refunds()), "refunds are not Shop B's");
        assertEquals(0, SALE_B.subtract(REFUND_B).compareTo(b.netSales()));
        assertEquals(0, SALE_B.compareTo(b.collectedOnline()),
                "the payment was taken online and belongs in the collected total even though "
                        + "part of it was later refunded - the refund line is where money going "
                        + "back is reported");
        assertEquals(1L, b.orderCount(), "Shop B placed one order in the window");
    }

    @Test
    @DisplayName("a refunded payment still counts as collected, so the statement reconciles")
    void aRefundedPaymentWasStillCollected() {
        ShopEarnings.Statement b = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> earnings.forCurrentShop(2));

        assertEquals(0, b.grossSales().compareTo(b.collectedOnline()),
                "dropping a refunded payment from the collected total would make it disagree "
                        + "with gross sales for any window containing a refund");
    }

    // --------------------------------------------------- the platform roll-up

    @Test
    @DisplayName("the platform sees one line per shop, each with that shop's own figures")
    void theMarketRollUpKeepsShopsApart() {
        List<ShopEarnings.ShopLine> lines = TenantContext.runWithin(TenantScope.platform(),
                () -> earnings.byShop(2));

        ShopEarnings.ShopLine a = lineFor(lines, shopA);
        ShopEarnings.ShopLine b = lineFor(lines, shopB);

        assertNotNull(a, "the platform overview lost Shop A");
        assertNotNull(b, "the platform overview lost Shop B");
        assertEquals(0, SALE_B.compareTo(b.grossSales()), "Shop B's line is not Shop B's money");
        assertEquals(0, REFUND_B.compareTo(b.refunds()), "Shop B's line carries other refunds");
        assertNotEquals(a.grossSales(), b.grossSales(),
                "both shops showing the same figure means the roll-up is pooling, not grouping");
    }

    @Test
    @DisplayName("the same roll-up under a shop scope returns that shop alone, so it fails closed")
    void theRollUpNarrowsRatherThanWidensWhenMisScoped() {
        List<ShopEarnings.ShopLine> asShopB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> earnings.byShop(2));

        assertEquals(1, asShopB.size(),
                "if the platform-admin gate were ever removed, a shopkeeper reaching the market "
                        + "overview must read one line - their own - not the marketplace's");
        assertEquals(shopB, asShopB.get(0).shopId());
    }

    // -------------------------------------------------------- who may look

    @Test
    @DisplayName("a shop ADMIN cannot open the marketplace overview, however senior they are")
    void aShopAdminIsNotAPlatformAdmin() throws Exception {
        mockMvc.perform(get("/api/platform/overview")
                        .with(authentication(as(Role.ADMIN, customerId))))
                .andExpect(result -> assertEquals(403, result.getResponse().getStatus(),
                        "a shop ADMIN reached the marketplace overview. RolePermissions builds "
                                + "every shop's set by SUBTRACTING PLATFORM_ADMIN precisely so "
                                + "that seniority inside one shop never becomes seniority over "
                                + "the market"));
    }

    @Test
    @DisplayName("under SINGLE_SHOP the overview is about Shop #1, because there is one answer")
    void singleShopModeStillResolvesToShopOne() throws Exception {
        // §2: the existing deployment must keep working unchanged. Under one
        // shop TenantResolver answers Shop #1 for everybody - no token carries
        // a shop claim and none ever will - so even a platform administrator's
        // request is about Shop #1 here. The cross-shop half of this endpoint
        // is proved in MarketplaceOversightTest, which runs the marketplace
        // mode it belongs to.
        String body = mockMvc.perform(get("/api/platform/overview?days=2")
                        .with(authentication(as(Role.PLATFORM_ADMIN, customerId))))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"shopId\":" + shopA),
                "Shop #1 is missing from its own deployment's overview: " + body);
        assertFalse(body.contains("\"shopId\":" + shopB),
                "a SINGLE_SHOP deployment answered with a second shop's figures, which means "
                        + "the mode is no longer deciding the scope");
    }

    @Test
    @DisplayName("a customer cannot read a shop's earnings")
    void aCustomerCannotReadTheTill() throws Exception {
        mockMvc.perform(get("/api/shop/earnings")
                        .with(authentication(as(Role.CUSTOMER, customerId))))
                .andExpect(result -> assertEquals(403, result.getResponse().getStatus(),
                        "a customer read a shop's takings"));
    }

    // ------------------------------------------------------------- fixtures

    private ShopEarnings.ShopLine lineFor(List<ShopEarnings.ShopLine> lines, long shopId) {
        return lines.stream().filter(l -> l.shopId() != null && l.shopId() == shopId)
                .findFirst().orElse(null);
    }

    private long unitsForProbeProduct(long shopId) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            for (Object[] row : orderItems.findTopProductsByUnits(
                    dayAgo(), TenantContext.reportingShopId(), PageRequest.of(0, 100000))) {
                if (productId.equals(((Number) row[0]).longValue())) {
                    return ((Number) row[1]).longValue();
                }
            }
            return 0L;
        });
    }

    private long countForProbeProduct(long shopId) {
        return TenantContext.runWithin(TenantScope.ofShop(shopId), () -> {
            for (Object[] row : orderItems.findTrendingProductIds(
                    dayAgo(), TenantContext.reportingShopId(), PageRequest.of(0, 100000))) {
                if (productId.equals(((Number) row[0]).longValue())) {
                    return ((Number) row[1]).longValue();
                }
            }
            return 0L;
        });
    }

    private static LocalDateTime dayAgo() {
        return LocalDateTime.now().minusDays(1);
    }

    private static LocalDateTime soon() {
        return LocalDateTime.now().plusDays(1);
    }

    private long insertOrder(String number, long shopId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id)
                VALUES (?, ?, ?, 'DELIVERED', 'SUCCESS', now(), ?)
                """, customerId, number, amount, shopId);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, number);
    }

    private void insertLine(long orderId, int quantity, BigDecimal total) {
        jdbc.update("""
                INSERT INTO order_items (id, order_id, product_variant_id, quantity, price,
                                         total_price, active)
                VALUES (nextval('order_items_seq'), ?, ?, ?, ?, ?, true)
                """, orderId, variantId, quantity,
                total.divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP), total);
    }

    private Long insertPayment(long orderId, long shopId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO payments (id, order_id, amount, payment_method, payment_status,
                                      active, shop_id)
                VALUES (nextval('payments_id_seq'), ?, ?, 'ONLINE', 'PARTIALLY_REFUNDED', true, ?)
                """, orderId, amount, shopId);
        return jdbc.queryForObject(
                "SELECT id FROM payments WHERE order_id = ?", Long.class, orderId);
    }

    private void insertRefund(Long paymentId, BigDecimal amount, String refundId) {
        jdbc.update("""
                INSERT INTO refunds (payment_id, amount, status, created_at, channel, refund_id,
                                     sequence_no)
                VALUES (?, ?, 'SUCCEEDED', now(), 'GATEWAY', ?, 1)
                """, paymentId, amount, refundId);
    }

    /** Exactly the principal JwtFilter builds, for whichever role is being tested. */
    private UsernamePasswordAuthenticationToken as(Role role, Long accountId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String authority : RolePermissions.authorityNames(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(accountId, tag + "@example.test", role.name()),
                null, authorities);
    }
}
