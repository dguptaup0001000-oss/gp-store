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
    public record MerchantInsight(LocalDateTime from, LocalDateTime to,
                                  long searchesNearby, long zeroResultSearchesNearby,
                                  long openDemandRequests, long demandResponses,
                                  long visitInterest, long serviceInterest,
                                  long completedOrders, List<DemandTerm> frequentSearches,
                                  List<DemandTerm> unmetDemand) {}
    public record ModeUsage(String commerceMode, long listings, long engagementEvents) {}
    public record MarketplaceInsight(LocalDateTime from, LocalDateTime to,
                                     long searches, long zeroResultSearches,
                                     BigDecimal zeroResultRate, long demandRequests,
                                     long merchantResponses, BigDecimal responseRate,
                                     long matchedRequests, long approvedAiDrafts,
                                     long failedAiJobs, List<DemandTerm> unmetDemand,
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
        return new MerchantInsight(from, to, searches, zero, requests, responses, visit, service,
                orders, frequent, unmet);
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
        List<ModeUsage> modes = jdbc.query("""
                SELECT m.mode, count(DISTINCT spv.id) listings,
                       count(DISTINCT e.id) engagements
                  FROM (VALUES ('ONLINE_PURCHASE'),('VISIT_TO_BUY'),('SERVICE_AT_SHOP')) m(mode)
                  LEFT JOIN shop_product_variants spv ON spv.commerce_mode=m.mode
                  LEFT JOIN listing_engagement_events e ON e.commerce_mode=m.mode AND e.occurred_at>=?
                 GROUP BY m.mode ORDER BY m.mode
                """, (rs, n) -> new ModeUsage(rs.getString("mode"), rs.getLong("listings"),
                rs.getLong("engagements")), from);
        return new MarketplaceInsight(from, to, searches, zero, rate(zero, searches),
                requests, responses, rate(responses, requests), matched, approved, failures,
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
