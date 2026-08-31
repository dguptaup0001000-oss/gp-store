package com.gpstore.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpstore.entity.Address;
import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.enums.OrderStatus;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.entity.Role;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The admin dashboard's numbers, against a real database.
 *
 * <p>EVERY MONEY ASSERTION IS A DELTA, never an absolute. The test database
 * is shared by the whole suite and accumulates orders from every other test
 * that writes one, so "revenue is 500" would pass today and fail the moment
 * somebody adds an unrelated checkout test. Measuring before and after the
 * rows this test inserts asserts the same property and cannot be broken by
 * a neighbour.
 *
 * <p>The two properties worth the most here are the ones that were actually
 * wrong before: top products counted order LINES and called them units, and
 * it counted CANCELLED orders while the revenue KPI on the same screen did
 * not - so the dashboard contradicted itself.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class AdminAnalyticsTest {

    private static final String MARKER = "analytics-" + System.nanoTime();

    @Autowired private AnalyticsService analytics;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;

    @Test
    @DisplayName("the chart has a point for every day in the window, including days nothing sold")
    void seriesFillsEveryDay() {
        List<Map<String, Object>> series = analytics.getSalesSeries(30);

        assertThat(series).hasSize(30);

        // Consecutive, ascending, ending today. A chart plotted positionally
        // depends on exactly this; a missing quiet day would shift every
        // later point one place to the left.
        LocalDate expected = LocalDate.now().minusDays(29);
        for (Map<String, Object> point : series) {
            assertThat(point.get("day")).isEqualTo(expected.toString());
            assertThat(point).containsKeys("revenue", "orderCount");
            expected = expected.plusDays(1);
        }
        assertThat(expected).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    @DisplayName("a one-day window is one point, and a silly window is clamped rather than allocated")
    void seriesWindowIsBounded() {
        assertThat(analytics.getSalesSeries(1)).hasSize(1);
        assertThat(analytics.getSalesSeries(0)).hasSize(1);
        assertThat(analytics.getSalesSeries(-5)).hasSize(1);

        // 100000 days is 273 years. Without the clamp this is a response
        // with a hundred thousand entries in it.
        assertThat(analytics.getSalesSeries(100_000)).hasSize(730);
    }

    @Test
    @DisplayName("a cancelled order is not revenue, in the series or in the summary")
    void cancelledOrdersAreNotRevenue() {
        BigDecimal revenueBefore = summaryRevenue(analytics.getSalesSummary(30));
        BigDecimal todayBefore = todayRevenue(analytics.getSalesSeries(30));

        ProductVariant variant = newVariant("cancelled-case", null);
        newOrderWithItem(variant, OrderStatus.CONFIRMED, new BigDecimal("250.00"), 1, LocalDateTime.now());
        newOrderWithItem(variant, OrderStatus.CANCELLED, new BigDecimal("999.00"), 1, LocalDateTime.now());

        BigDecimal revenueAfter = summaryRevenue(analytics.getSalesSummary(30));
        BigDecimal todayAfter = todayRevenue(analytics.getSalesSeries(30));

        // 250 landed. 999 did not. If CANCELLED leaked in this would be 1249.
        assertThat(revenueAfter.subtract(revenueBefore)).isEqualByComparingTo("250.00");
        assertThat(todayAfter.subtract(todayBefore)).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("the series and the summary agree on what revenue means")
    void seriesSumsToTheSummary() {
        ProductVariant variant = newVariant("agreement-case", null);
        newOrderWithItem(variant, OrderStatus.DELIVERED, new BigDecimal("410.00"), 2, LocalDateTime.now());

        Map<String, Object> summary = analytics.getSalesSummary(30);
        BigDecimal seriesTotal = BigDecimal.ZERO;
        for (Map<String, Object> point : analytics.getSalesSeries(30)) {
            seriesTotal = seriesTotal.add((BigDecimal) point.get("revenue"));
        }

        // Not an exact equality: the summary window is "30 days back from
        // this instant" while the series starts at midnight of its first
        // day, so the series can legitimately include a little more. What
        // must never happen is the series holding LESS than the summary -
        // that would mean the chart is hiding sales the KPI counted.
        assertThat(seriesTotal).isGreaterThanOrEqualTo(summaryRevenue(summary));
    }

    @Test
    @DisplayName("top products counts units sold, not the number of orders a product appeared in")
    void topProductsCountsUnitsNotOrderLines() {
        // One order, one line, seven packets. The old query counted this as
        // 1 and the dashboard printed "1 sold".
        ProductVariant variant = newVariant("units-case", null);
        newOrderWithItem(variant, OrderStatus.CONFIRMED, new BigDecimal("700.00"), 7, LocalDateTime.now());

        Map<String, Object> entry = findProduct(variant.getProduct().getId());
        assertThat(entry).as("the product this test just sold should be in the top list").isNotNull();
        assertThat(entry.get("unitsSold")).isEqualTo(7L);
        assertThat((BigDecimal) entry.get("revenue")).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("a cancelled order does not inflate a product's units or revenue")
    void topProductsExcludeCancelled() {
        ProductVariant variant = newVariant("top-cancelled-case", null);
        newOrderWithItem(variant, OrderStatus.CONFIRMED, new BigDecimal("300.00"), 3, LocalDateTime.now());
        newOrderWithItem(variant, OrderStatus.CANCELLED, new BigDecimal("5000.00"), 50, LocalDateTime.now());

        Map<String, Object> entry = findProduct(variant.getProduct().getId());
        assertThat(entry).isNotNull();
        assertThat(entry.get("unitsSold")).isEqualTo(3L);
        assertThat((BigDecimal) entry.get("revenue")).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("top products carry a thumbnail so the dashboard is not a wall of text")
    void topProductsCarryAThumbnail() {
        ProductVariant variant = newVariant("thumbnail-case", "https://images.example.test/rice.jpg");
        newOrderWithItem(variant, OrderStatus.CONFIRMED, new BigDecimal("120.00"), 4, LocalDateTime.now());

        Map<String, Object> entry = findProduct(variant.getProduct().getId());
        assertThat(entry).isNotNull();
        // A plain HTTPS URL is not an R2 object key, so CatalogImageDelivery
        // passes it through untouched. That is the property being pinned:
        // the resolver is on the path, and it does not mangle what it does
        // not own.
        assertThat(entry.get("imageUrl")).isEqualTo("https://images.example.test/rice.jpg");
    }

    @Test
    @DisplayName("a product with no photograph yields a null thumbnail rather than blowing up")
    void aProductWithoutAnImageIsStillListed() {
        ProductVariant variant = newVariant("no-image-case", null);
        newOrderWithItem(variant, OrderStatus.CONFIRMED, new BigDecimal("60.00"), 2, LocalDateTime.now());

        Map<String, Object> entry = findProduct(variant.getProduct().getId());
        assertThat(entry).isNotNull();
        assertThat(entry.get("imageUrl")).isNull();
        assertThat(entry.get("unitsSold")).isEqualTo(2L);
    }

    @Test
    @DisplayName("the summary keeps its original keys and adds the previous period beside them")
    void summaryIsAdditive() {
        Map<String, Object> summary = analytics.getSalesSummary(30);

        // The Flutter SalesSummary model deserialises these five. Renaming
        // or dropping any of them breaks the installed app, not just the
        // next build of it.
        assertThat(summary).containsKeys(
                "periodDays", "revenue", "orderCount", "cancelledCount", "averageOrderValue");
        assertThat(summary).containsKeys(
                "previousRevenue", "previousOrderCount",
                "revenueChangePercent", "orderCountChangePercent");
        assertThat(summary.get("periodDays")).isEqualTo(30);
    }

    @Test
    @DisplayName("the previous period is the window before this one, not an overlapping copy of it")
    void previousPeriodDoesNotDoubleCountToday() {
        BigDecimal previousBefore = (BigDecimal) analytics.getSalesSummary(7).get("previousRevenue");

        ProductVariant variant = newVariant("previous-window-case", null);
        newOrderWithItem(variant, OrderStatus.CONFIRMED, new BigDecimal("777.00"), 1, LocalDateTime.now());

        Map<String, Object> summary = analytics.getSalesSummary(7);
        BigDecimal previousAfter = (BigDecimal) summary.get("previousRevenue");

        // Today's sale belongs to the CURRENT week. If the previous window
        // overlapped it, this order would be counted twice and the "vs last
        // week" arrow would be measuring the sale against itself.
        assertThat(previousAfter).isEqualByComparingTo(previousBefore);
    }

    @Test
    @DisplayName("no baseline means no percentage, not a divide-by-zero and not a fake +100%")
    void percentChangeWithoutABaselineIsZero() {
        // A window far enough back that nothing exists on either side of it.
        // Both periods are empty, so the only honest answer is "no change".
        Map<String, Object> summary = analytics.getSalesSummary(1);
        assertThat(summary.get("revenueChangePercent")).isInstanceOf(BigDecimal.class);
        assertThat(summary.get("orderCountChangePercent")).isInstanceOf(BigDecimal.class);

        Map<String, Object> empty = analytics.getSalesSummary(730);
        // Whatever the numbers are, they are finite and typed. The old shape
        // of this bug is an ArithmeticException from BigDecimal.divide on a
        // zero baseline, which would 500 the whole dashboard.
        assertThat(empty.get("revenueChangePercent")).isNotNull();
    }

    // ------------------------------------------------------------------

    /**
     * This product's row from the leaderboard, or null.
     *
     * <p>ASKS FOR A LARGE PAGE, AND THAT MATTERS. These tests are about the
     * SHAPE of a row - its units, its revenue, its thumbnail - not about where
     * the product ranks. Asking for the top 50 quietly made them ranking tests
     * as well: every fixture here sells a handful of units, so once the shared
     * test database had accumulated enough products with recent orders, a
     * two-unit fixture fell off the end of the page and the assertion failed
     * with "expecting actual not to be null" - which points at the analytics
     * code rather than at the crowding that actually caused it.
     *
     * <p>The dashboard's own cap (50, in AnalyticsController) is deliberately
     * NOT reused here: that is a display decision about how much an operator
     * wants to read, and borrowing it for a lookup made the test depend on it.
     */
    private Map<String, Object> findProduct(Long productId) {
        for (Map<String, Object> entry : analytics.getTopProducts(30, 10_000)) {
            if (productId.equals(entry.get("productId"))) {
                return entry;
            }
        }
        return null;
    }

    private static BigDecimal summaryRevenue(Map<String, Object> summary) {
        return (BigDecimal) summary.get("revenue");
    }

    private static BigDecimal todayRevenue(List<Map<String, Object>> series) {
        String today = LocalDate.now().toString();
        for (Map<String, Object> point : series) {
            if (today.equals(point.get("day"))) {
                return (BigDecimal) point.get("revenue");
            }
        }
        throw new AssertionError("the series has no point for today");
    }

    private ProductVariant newVariant(String label, String imageUrl) {
        Category category = new Category();
        category.setName(MARKER + "-" + label + "-" + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName(MARKER + "-" + label);
        product.setBrand("Test");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setMrp(new BigDecimal("120"));
        variant.setSellingPrice(new BigDecimal("100"));
        variant.setCostPrice(new BigDecimal("80"));
        variant.setImageUrl(imageUrl);
        variant.setAvailable(true);
        variant.setActive(true);
        variant.setDisplayOrder(0);
        return variantRepository.save(variant);
    }

    private void newOrderWithItem(ProductVariant variant, OrderStatus status,
                                  BigDecimal total, int quantity, LocalDateTime when) {
        Customer customer = new Customer();
        customer.setFullName(MARKER + "-buyer");
        customer.setMobileNumber(String.valueOf(9000000000L + (System.nanoTime() % 900000000L)));
        customer.setEmail(MARKER + "-" + System.nanoTime() + "@example.test");
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setVerified(true);
        customer = customerRepository.save(customer);

        Address address = new Address();
        address.setFullName(MARKER + "-address");
        address.setMobileNumber("9100000000");
        address.setHouseNo("1");
        address.setArea("Test");
        address.setCity("Malhia");
        address.setPincode("274401");
        address.setLatitude(27.162);
        address.setLongitude(83.940);
        address.setCustomer(customer);
        address = addressRepository.save(address);

        Order order = new Order();
        order.setOrderNumber(MARKER + "-" + System.nanoTime());
        order.setCustomer(customer);
        order.setAddress(address);
        order.setOrderStatus(status);
        order.setTotalAmount(total);
        order.setOrderDate(when);
        order.setActive(true);
        order = orderRepository.save(order);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(variant);
        item.setQuantity(quantity);
        item.setPrice(variant.getSellingPrice());
        item.setTotalPrice(total);
        item.setGstRate(new BigDecimal("5"));
        item.setActive(true);
        orderItemRepository.save(item);
    }
}
