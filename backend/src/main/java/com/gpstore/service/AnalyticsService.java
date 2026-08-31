package com.gpstore.service;

import com.gpstore.entity.Product;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    /**
     * Upper bound on the window any dashboard query may ask for.
     *
     * The chart fills every day in the window, so an unbounded {@code days}
     * is an unbounded response: {@code ?days=100000} would build a hundred
     * thousand map entries and serialise them, from an endpoint that only
     * ever needs a few months. Two years is far past any view the console
     * offers and still trivially cheap.
     */
    private static final int MAX_PERIOD_DAYS = 730;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;

    public AnalyticsService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             ProductRepository productRepository,
                             ProductVariantRepository productVariantRepository,
                             InventoryRepository inventoryRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Revenue deliberately excludes CANCELLED orders - a cancelled order was
     * never real revenue and including it would overstate how the business is
     * actually doing.
     *
     * <p>ALSO RETURNS THE PREVIOUS PERIOD, so the dashboard can show "+12%
     * vs last month" without a second round trip and without the client
     * inventing its own comparison window. The previous period is the same
     * number of days immediately before this one, so a 30-day view compares
     * against the 30 days before it - not against a calendar month, which
     * would compare 30 days with 28 in February and report a fake decline.
     *
     * <p>The original five keys are unchanged. Existing clients keep
     * deserialising; the delta fields are additive.
     */
    public Map<String, Object> getSalesSummary(int days) {
        int window = clampDays(days);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(window);
        LocalDateTime previousFrom = from.minusDays(window);

        BigDecimal revenue = orderRepository.sumRevenueBetween(from, to);
        long orderCount = orderRepository.countOrdersBetween(from, to);
        long cancelledCount = orderRepository.countCancelledBetween(from, to);

        // The previous window ends where this one begins. `from` itself is
        // excluded by using it as the exclusive-in-spirit upper bound of the
        // earlier window: sumRevenueBetween is inclusive on both ends, so an
        // order landing exactly on the boundary instant would otherwise be
        // counted in both periods. One nanosecond back is enough, and
        // order_date has microsecond resolution in Postgres.
        LocalDateTime previousTo = from.minusNanos(1_000);
        BigDecimal previousRevenue = orderRepository.sumRevenueBetween(previousFrom, previousTo);
        long previousOrderCount = orderRepository.countOrdersBetween(previousFrom, previousTo);

        BigDecimal averageOrderValue = orderCount == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("periodDays", window);
        summary.put("revenue", revenue);
        summary.put("orderCount", orderCount);
        summary.put("cancelledCount", cancelledCount);
        summary.put("averageOrderValue", averageOrderValue);
        summary.put("previousRevenue", previousRevenue);
        summary.put("previousOrderCount", previousOrderCount);
        summary.put("revenueChangePercent", percentChange(previousRevenue, revenue));
        summary.put("orderCountChangePercent",
                percentChange(BigDecimal.valueOf(previousOrderCount), BigDecimal.valueOf(orderCount)));
        return summary;
    }

    /**
     * Daily revenue and order count for the dashboard chart.
     *
     * <p>EVERY DAY IN THE WINDOW IS PRESENT, including days nothing sold.
     * The database only returns days that have orders, and a chart drawn
     * straight from that draws a quiet Tuesday as if it never existed -
     * the line jumps from Monday to Wednesday and the shape of the week is
     * a lie. Filling the gaps with zero here means the client can plot the
     * list positionally and never has to reason about dates.
     *
     * <p>Days are bucketed by the SERVER's local date, which is what
     * date_trunc on a timestamp gives us and what the shop actually means
     * by "today".
     */
    public List<Map<String, Object>> getSalesSeries(int days) {
        int window = clampDays(days);
        LocalDateTime to = LocalDateTime.now();
        LocalDate lastDay = to.toLocalDate();
        LocalDate firstDay = lastDay.minusDays(window - 1L);
        // Start at midnight of the first day so a partial first day is not
        // half-counted: the chart's leftmost bucket is a whole day.
        LocalDateTime from = firstDay.atStartOfDay();

        Map<LocalDate, Object[]> byDay = new HashMap<>();
        for (Object[] row : orderRepository.revenueByDayBetween(from, to)) {
            LocalDate day = toLocalDate(row[0]);
            if (day != null) {
                byDay.put(day, row);
            }
        }

        List<Map<String, Object>> series = new ArrayList<>(window);
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            Object[] row = byDay.get(day);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", day.toString());
            point.put("revenue", row == null ? BigDecimal.ZERO : toBigDecimal(row[1]));
            point.put("orderCount", row == null ? 0L : toLong(row[2]));
            series.add(point);
        }
        return series;
    }

    /** How many orders are currently in each stage - the "what needs attention right now" view. */
    public Map<String, Long> getOrderStatusBreakdown() {
        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (Object[] row : orderRepository.countByStatus()) {
            breakdown.put(row[0].toString(), (Long) row[1]);
        }
        return breakdown;
    }

    /**
     * Top products for the admin dashboard: units actually sold, the revenue
     * they earned, and a thumbnail.
     *
     * <p>NO LONGER THE CUSTOMER TRENDING QUERY. This used to reuse
     * findTrendingProductIds, which counts order LINES - so "1,245 sold"
     * meant "appeared in 1,245 orders", and a customer buying twelve packets
     * in one order counted as one. It also did not exclude CANCELLED, so the
     * leaderboard and the revenue KPI on the same screen disagreed about
     * which sales were real. findTopProductsByUnits fixes both; the customer
     * recommendation path is untouched and still uses the old query.
     *
     * <p>The limit goes to the DATABASE, and the product lookup is a single
     * batched findByIdIn rather than a findById per row.
     *
     * <p>Unlike the customer-facing lists this deliberately does NOT filter
     * on active - an admin looking at what sold last month needs to see a
     * product they have since withdrawn, which is exactly the kind of thing
     * they withdraw it for.
     */
    public List<Map<String, Object>> getTopProducts(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(clampDays(days));
        List<Object[]> rows = orderItemRepository.findTopProductsByUnits(
                since, PageRequest.of(0, Math.max(1, limit)));

        List<Long> productIds = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            productIds.add(toLong(row[0]));
        }
        if (productIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findByIdIn(productIds)) {
            byId.put(product.getId(), product);
        }
        Map<Long, String> thumbnails = thumbnailsFor(productIds);

        List<Map<String, Object>> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Long productId = toLong(row[0]);
            Product product = byId.get(productId);
            if (product == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("productId", product.getId());
            entry.put("productName", product.getName());
            entry.put("unitsSold", toLong(row[1]));
            entry.put("revenue", toBigDecimal(row[2]));
            entry.put("imageUrl", thumbnails.get(productId));
            results.add(entry);
        }
        return results;
    }

    public long getLowStockCount() {
        return inventoryRepository.countLowStock();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * One thumbnail per product, keyed by product id.
     *
     * <p>The repository returns every candidate variant ordered by product
     * then variant id, so the FIRST row for a product is the one to keep -
     * putIfAbsent expresses exactly that and needs no sorting here. The URL
     * goes through CatalogImageDelivery for the same reason every other
     * client-facing image does: R2 objects live in a private bucket and the
     * phone needs either a Worker URL or a short-lived signed GET, never the
     * raw object key.
     */
    private Map<Long, String> thumbnailsFor(List<Long> productIds) {
        Map<Long, String> thumbnails = new HashMap<>();
        for (Object[] row : productVariantRepository.findThumbnailCandidates(productIds)) {
            Long productId = toLong(row[0]);
            String stored = (String) row[1];
            if (productId == null || stored == null) {
                continue;
            }
            thumbnails.putIfAbsent(productId,
                    com.gpstore.upload.CatalogImageDelivery.forClient(stored));
        }
        return thumbnails;
    }

    /**
     * Growth from {@code previous} to {@code current}, rounded to one
     * decimal.
     *
     * <p>ZERO WHEN THERE IS NO BASELINE, not infinity and not "+100%". A
     * shop's first week has no previous week; dividing by it would produce
     * either an arithmetic exception or a meaningless number the dashboard
     * would render as a triumphant green arrow. Returning zero makes the
     * client show "no comparison" instead of a fiction.
     */
    private static BigDecimal percentChange(BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null || previous.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static int clampDays(int days) {
        if (days < 1) {
            return 1;
        }
        return Math.min(days, MAX_PERIOD_DAYS);
    }

    /**
     * date_trunc comes back as a java.sql.Timestamp on Postgres, but the
     * exact class is a JDBC driver detail rather than a contract, so accept
     * the shapes a driver may plausibly hand back instead of casting and
     * hoping.
     */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return null;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
