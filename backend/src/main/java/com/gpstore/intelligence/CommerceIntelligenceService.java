package com.gpstore.intelligence;

import com.gpstore.platform.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Server-side, thresholded intelligence. No response contains a customer id,
 * raw coordinate, contact detail, or individual search history.
 */
@Service
public class CommerceIntelligenceService {
    private final JdbcTemplate jdbc;
    private final int privacyThreshold;

    public CommerceIntelligenceService(
            JdbcTemplate jdbc,
            @Value("${marketplace.analytics.privacy-threshold:3}") int privacyThreshold) {
        this.jdbc = jdbc;
        this.privacyThreshold = Math.max(3, privacyThreshold);
    }

    public record DemandTerm(String query, long searches, long zeroResultSearches) {}
    public record TrendPoint(String period, long orders, BigDecimal sales) {}
    public record PopularPeriod(int hour, long orders) {}
    public record MerchantInsight(LocalDateTime from, LocalDateTime to,
                                  long searchesNearby, long zeroResultSearchesNearby,
                                  long openDemandRequests, long demandResponses,
                                  long visitInterest, long serviceInterest,
                                  long completedOrders, long currentOutOfStockListings,
                                  long repeatCustomers, BigDecimal grossSales,
                                  List<DemandTerm> frequentSearches,
                                  List<DemandTerm> unmetDemand,
                                  List<DemandTerm> searchedButNotStocked,
                                  List<TrendPoint> orderTrend,
                                  List<PopularPeriod> popularPeriods) {}
    public record ModeUsage(String commerceMode, long listings, long engagementEvents) {}
    public record MarketplaceInsight(LocalDateTime from, LocalDateTime to,
                                     long searches, long zeroResultSearches,
                                     long lowResultSearches,
                                     BigDecimal zeroResultRate, long demandRequests,
                                     long merchantResponses, BigDecimal responseRate,
                                     long matchedRequests, long approvedAiDrafts,
                                     long failedAiJobs, long productDiscoveryEvents,
                                     long directionRequests, long shopCalls,
                                     long completedOrders, long activeSupplyShops,
                                     long coveredCategories,
                                     List<DemandTerm> unmetDemand,
                                     List<ModeUsage> commerceModeUsage) {}

    @Transactional(readOnly = true)
    public MerchantInsight merchant(int days) {
        long shopId = TenantContext.require().requireShopId();
        int safeDays = Math.min(Math.max(days, 1), 365);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(safeDays);
        java.util.Map<String, Object> shop = jdbc.queryForMap(
                "SELECT latitude, longitude FROM shops WHERE id=?", shopId);
        Double lat = (Double) shop.get("latitude");
        Double lng = (Double) shop.get("longitude");
        BigDecimal latCell = lat == null ? null : BigDecimal.valueOf(lat).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal lngCell = lng == null ? null : BigDecimal.valueOf(lng).setScale(2, java.math.RoundingMode.HALF_UP);
        long searches = count("""
                SELECT count(*) FROM marketplace_search_events
                 WHERE created_at>=? AND (?::numeric IS NULL OR abs(latitude_cell-?)<=0.10)
                   AND (?::numeric IS NULL OR abs(longitude_cell-?)<=0.10)
                """, from, latCell, latCell, lngCell, lngCell);
        long zero = count("""
                SELECT count(*) FROM marketplace_search_events
                 WHERE created_at>=? AND result_count=0
                   AND (?::numeric IS NULL OR abs(latitude_cell-?)<=0.10)
                   AND (?::numeric IS NULL OR abs(longitude_cell-?)<=0.10)
                """, from, latCell, latCell, lngCell, lngCell);
        List<DemandTerm> frequent = terms(from, latCell, lngCell, false);
        List<DemandTerm> unmet = terms(from, latCell, lngCell, true);
        long requests = count("""
                SELECT count(*) FROM demand_request_recipients dr JOIN demand_requests d ON d.id=dr.request_id
                 WHERE dr.shop_id=? AND d.created_at>=? AND d.status='OPEN' AND d.expires_at>now()
                """, shopId, from);
        long responses = count("SELECT count(*) FROM demand_responses WHERE shop_id=? AND created_at>=?",
                shopId, from);
        long visit = count("""
                SELECT count(*) FROM listing_engagement_events
                 WHERE shop_id=? AND occurred_at>=? AND commerce_mode='VISIT_TO_BUY'
                """, shopId, from);
        long service = count("""
                SELECT count(*) FROM listing_engagement_events
                 WHERE shop_id=? AND occurred_at>=? AND commerce_mode='SERVICE_AT_SHOP'
                """, shopId, from);
        long orders = count("""
                SELECT count(*) FROM orders
                 WHERE shop_id=? AND order_date>=? AND order_status='DELIVERED'
                """, shopId, from);
        long outOfStock = count("""
                SELECT count(*) FROM shop_product_variants spv
                  LEFT JOIN inventory i
                    ON i.shop_id=spv.shop_id AND i.product_variant_id=spv.product_variant_id
                 WHERE spv.shop_id=? AND spv.active=true
                   AND COALESCE(i.stock, 0)<=0
                """, shopId);
        long repeatCustomers = count("""
                SELECT count(*) FROM (
                    SELECT customer_id FROM orders
                     WHERE shop_id=? AND order_date>=? AND order_status='DELIVERED'
                     GROUP BY customer_id HAVING count(*)>1
                ) repeat_buyers
                """, shopId, from);
        if (repeatCustomers < privacyThreshold) repeatCustomers = 0;
        BigDecimal grossSales = jdbc.queryForObject("""
                SELECT COALESCE(sum(total_amount),0) FROM orders
                 WHERE shop_id=? AND order_date>=? AND order_status='DELIVERED'
                """, BigDecimal.class, shopId, from);
        List<DemandTerm> notStocked = searchedButNotStocked(
                shopId, from, latCell, lngCell);
        List<TrendPoint> trend = jdbc.query("""
                SELECT to_char(date_trunc('day', order_date), 'YYYY-MM-DD') period,
                       count(*) orders, COALESCE(sum(total_amount),0) sales
                  FROM orders
                 WHERE shop_id=? AND order_date>=? AND order_status='DELIVERED'
                 GROUP BY date_trunc('day', order_date)
                 ORDER BY date_trunc('day', order_date)
                """, (rs, n) -> new TrendPoint(rs.getString("period"), rs.getLong("orders"),
                rs.getBigDecimal("sales")), shopId, from);
        List<PopularPeriod> popular = jdbc.query("""
                SELECT extract(hour from order_date)::int AS order_hour, count(*) AS orders
                  FROM orders WHERE shop_id=? AND order_date>=?
                 GROUP BY extract(hour from order_date)
                 HAVING count(*)>=?
                 ORDER BY orders DESC, order_hour LIMIT 8
                """, (rs, n) -> new PopularPeriod(
                        rs.getInt("order_hour"), rs.getLong("orders")),
                shopId, from, privacyThreshold);
        return new MerchantInsight(from, to, searches, zero, requests, responses, visit, service,
                orders, outOfStock, repeatCustomers,
                grossSales == null ? BigDecimal.ZERO : grossSales,
                frequent, unmet, notStocked, trend, popular);
    }

    @Transactional(readOnly = true)
    public MarketplaceInsight platform(int days) {
        int safeDays = Math.min(Math.max(days, 1), 365);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(safeDays);
        long searches = count("SELECT count(*) FROM marketplace_search_events WHERE created_at>=?", from);
        long zero = count("""
                SELECT count(*) FROM marketplace_search_events WHERE created_at>=? AND result_count=0
                """, from);
        long low = count("""
                SELECT count(*) FROM marketplace_search_events
                 WHERE created_at>=? AND result_count BETWEEN 1 AND 2
                """, from);
        long requests = count("SELECT count(*) FROM demand_requests WHERE created_at>=?", from);
        long responses = count("SELECT count(*) FROM demand_responses WHERE created_at>=?", from);
        long matched = count("""
                SELECT count(DISTINCT request_id) FROM demand_responses
                 WHERE created_at>=? AND status='AVAILABLE'
                """, from);
        long approved = count("""
                SELECT count(*) FROM ai_catalog_drafts WHERE approved_at>=? AND status='APPROVED'
                """, from);
        long failures = count("""
                SELECT count(*) FROM ai_extraction_jobs WHERE created_at>=? AND status='FAILED'
                """, from);
        long discoveryEvents = count("""
                SELECT count(*) FROM listing_engagement_events
                 WHERE occurred_at>=? AND kind IN ('VIEWED_CARD','OPENED_DETAIL')
                """, from);
        long directions = count("""
                SELECT count(*) FROM listing_engagement_events
                 WHERE occurred_at>=? AND kind='ASKED_DIRECTIONS'
                """, from);
        long calls = count("""
                SELECT count(*) FROM listing_engagement_events
                 WHERE occurred_at>=? AND kind='CALLED_SHOP'
                """, from);
        long completedOrders = count("""
                SELECT count(*) FROM orders
                 WHERE order_date>=? AND order_status='DELIVERED'
                """, from);
        long activeSupplyShops = count("""
                SELECT count(DISTINCT shop_id) FROM shop_product_variants
                 WHERE active=true
                """);
        long coveredCategories = count("""
                SELECT count(DISTINCT p.category_id)
                  FROM shop_product_variants spv
                  JOIN product_variants v ON v.id=spv.product_variant_id
                  JOIN products p ON p.id=v.product_id
                 WHERE spv.active=true AND p.active=true
                """);
        List<ModeUsage> modes = jdbc.query("""
                SELECT m.mode, count(DISTINCT spv.id) listings,
                       count(DISTINCT e.id) engagements
                  FROM (VALUES ('ONLINE_PURCHASE'),('VISIT_TO_BUY'),('SERVICE_AT_SHOP')) m(mode)
                  LEFT JOIN shop_product_variants spv ON spv.commerce_mode=m.mode
                  LEFT JOIN listing_engagement_events e ON e.commerce_mode=m.mode AND e.occurred_at>=?
                 GROUP BY m.mode ORDER BY m.mode
                """, (rs, n) -> new ModeUsage(rs.getString("mode"), rs.getLong("listings"),
                rs.getLong("engagements")), from);
        return new MarketplaceInsight(from, to, searches, zero, low, rate(zero, searches),
                requests, responses, rate(responses, requests), matched, approved, failures,
                discoveryEvents, directions, calls, completedOrders,
                activeSupplyShops, coveredCategories,
                platformTerms(from), modes);
    }

    private List<DemandTerm> terms(LocalDateTime from, BigDecimal lat, BigDecimal lng, boolean zeroOnly) {
        return jdbc.query("""
                SELECT normalized_query, count(*) searches,
                       count(*) FILTER (WHERE result_count=0) zero_count
                  FROM marketplace_search_events
                 WHERE created_at>=?
                   AND (?::numeric IS NULL OR abs(latitude_cell-?)<=0.10)
                   AND (?::numeric IS NULL OR abs(longitude_cell-?)<=0.10)
                   AND (?=false OR result_count=0)
                 GROUP BY normalized_query HAVING count(*)>=?
                 ORDER BY %s DESC, normalized_query LIMIT 20
                """.formatted(zeroOnly ? "zero_count" : "searches"),
                (rs, n) -> new DemandTerm(rs.getString("normalized_query"),
                        rs.getLong("searches"), rs.getLong("zero_count")),
                from, lat, lat, lng, lng, zeroOnly, privacyThreshold);
    }

    private List<DemandTerm> platformTerms(LocalDateTime from) {
        return jdbc.query("""
                SELECT normalized_query, count(*) searches,
                       count(*) FILTER (WHERE result_count=0) zero_count
                  FROM marketplace_search_events WHERE created_at>=?
                 GROUP BY normalized_query HAVING count(*)>=?
                 ORDER BY zero_count DESC, searches DESC LIMIT 30
                """, (rs, n) -> new DemandTerm(rs.getString("normalized_query"),
                rs.getLong("searches"), rs.getLong("zero_count")), from, privacyThreshold);
    }

    private List<DemandTerm> searchedButNotStocked(long shopId, LocalDateTime from,
                                                    BigDecimal lat, BigDecimal lng) {
        return jdbc.query("""
                SELECT e.normalized_query, count(*) searches,
                       count(*) FILTER (WHERE e.result_count=0) zero_count
                  FROM marketplace_search_events e
                 WHERE e.created_at>=?
                   AND (?::numeric IS NULL OR abs(e.latitude_cell-?)<=0.10)
                   AND (?::numeric IS NULL OR abs(e.longitude_cell-?)<=0.10)
                   AND NOT EXISTS (
                       SELECT 1 FROM shop_product_variants spv
                       JOIN product_variants v ON v.id=spv.product_variant_id
                       JOIN products p ON p.id=v.product_id
                        WHERE spv.shop_id=? AND spv.active=true
                          AND (lower(p.name) LIKE '%%' || e.normalized_query || '%%'
                               OR lower(p.brand) LIKE '%%' || e.normalized_query || '%%'
                               OR lower(p.search_keywords) LIKE '%%' || e.normalized_query || '%%'))
                 GROUP BY e.normalized_query HAVING count(*)>=?
                 ORDER BY searches DESC, e.normalized_query LIMIT 20
                """, (rs, n) -> new DemandTerm(rs.getString("normalized_query"),
                rs.getLong("searches"), rs.getLong("zero_count")),
                from, lat, lat, lng, lng, shopId, privacyThreshold);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
