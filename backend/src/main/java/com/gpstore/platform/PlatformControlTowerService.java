package com.gpstore.platform;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.presence.PresenceSnapshot;
import com.gpstore.presence.PresenceTracker;
import com.gpstore.service.AuditLogService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read model for the platform owner's cockpit.
 *
 * This is intentionally a projection service over the existing commerce
 * tables. It does not own merchant lifecycle, orders, payments, refunds or
 * tenant authorization, and therefore cannot become a parallel business
 * system. All SQL is parameterized and every collection is bounded.
 */
@Service
public class PlatformControlTowerService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 120;

    private final NamedParameterJdbcTemplate jdbc;
    private final PresenceTracker presence;
    private final AuditLogService audit;

    public PlatformControlTowerService(NamedParameterJdbcTemplate jdbc,
                                       PresenceTracker presence,
                                       AuditLogService audit) {
        this.jdbc = jdbc;
        this.presence = presence;
        this.audit = audit;
    }

    public record PageEnvelope<T>(List<T> content, int page, int size,
                                  long totalElements, int totalPages) {}

    public record SearchResult(String entityType, Long entityId, String title,
                               String reference, String subtitle,
                               String maskedEmail, String maskedPhone) {}

    public record MarketplaceCounts(long totalMerchants, long activeMerchants,
                                    long pendingMerchants, long suspendedMerchants,
                                    long totalShops, long acceptingOrdersShops,
                                    long pausedShops, long closedShops,
                                    long suspendedShops, long totalCustomers,
                                    long activeCustomerAccounts, long newCustomers,
                                    long totalWorkers, long activeWorkers) {}

    public record FinanceSummary(BigDecimal gmv, BigDecimal completedSales,
                                 BigDecimal merchantProductSales,
                                 BigDecimal deliveryCharges, BigDecimal refunds,
                                 BigDecimal cancellationFees,
                                 BigDecimal platformCommission,
                                 BigDecimal platformFees,
                                 BigDecimal platformAdjustments,
                                 boolean platformRevenueAvailable) {}

    public record DashboardSummary(LocalDateTime from, LocalDateTime to,
                                   MarketplaceCounts marketplace,
                                   Map<String, Long> orderStatuses,
                                   FinanceSummary finance,
                                   Integer recentlyActiveAuthenticatedAccounts,
                                   Integer presenceWindowSeconds,
                                   boolean presenceAvailable) {}

    public record CustomerIdentity(Long id, String customerRef, String name,
                                   String maskedEmail, String maskedPhone,
                                   String role, Boolean enabled, Boolean active,
                                   Boolean verified, LocalDateTime createdAt) {}

    public record CustomerOrderSummary(long total, long completed, long active,
                                       long cancelled, long failed, long returned,
                                       long refunded) {}

    public record CustomerFinance(BigDecimal completedPurchaseValue,
                                  BigDecimal refunds, BigDecimal cancellationFees) {}

    public record RecentOrder(Long id, String orderNumber, Long shopId,
                              String shopName, String status, BigDecimal total,
                              LocalDateTime orderedAt) {}

    public record Customer360(CustomerIdentity identity, CustomerOrderSummary orders,
                              CustomerFinance finance, long reviews,
                              long reportedReviews, List<RecentOrder> recentOrders) {}

    public record RevealedPii(Long customerId, String field, String value) {}

    public record MerchantIdentity(Long id, String merchantRef, String legalName,
                                   String displayName, String ownerCustomerRef,
                                   String maskedEmail, String maskedPhone,
                                   String status, String statusReason, Boolean active,
                                   Boolean demo, String tier, LocalDateTime createdAt,
                                   LocalDateTime updatedAt) {}

    public record ShopLine(Long id, String shopRef, String code, String name,
                           String status, String statusReason, Boolean active,
                           String verification, String city, String state,
                           String orderAcceptance, long workers, long products,
                           long orders, BigDecimal gmv) {}

    public record Merchant360(MerchantIdentity identity, List<ShopLine> shops,
                              long totalOrders, long completedOrders,
                              long cancelledOrders, BigDecimal gmv,
                              BigDecimal deliveryCharges, BigDecimal refunds,
                              BigDecimal merchantProductSales,
                              BigDecimal platformCommission,
                              BigDecimal platformFees) {}

    public record Shop360(ShopLine shop, Long merchantId, String merchantRef,
                          String merchantName, String addressLine, String locality,
                          String pincode, BigDecimal maxDeliveryRadiusKm,
                          String timeZone, BigDecimal refunds,
                          BigDecimal cancellationFees, long reviews,
                          BigDecimal averageRating) {}

    private record ResourceSql(String select, String from, String predicate,
                               String orderBy) {}

    public record ResourceFilters(String status, Long merchantId, Long shopId,
                                  Long customerId, Long workerId,
                                  String paymentStatus, String paymentMethod,
                                  String category, String stockStatus,
                                  LocalDateTime from, LocalDateTime to) {}

    public record DashboardFilters(Long merchantId, Long shopId,
                                   String orderStatus, String paymentStatus,
                                   String paymentMethod) {}

    @Transactional
    public PageEnvelope<SearchResult> search(String rawQuery, int requestedPage, int requestedSize) {
        String term = rawQuery == null ? "" : rawQuery.trim();
        if (term.length() < 2) {
            throw new BadRequestException("Search requires at least 2 characters");
        }
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new BadRequestException("Search is limited to 120 characters");
        }
        int page = Math.max(0, requestedPage);
        int size = pageSize(requestedSize);
        long offset = Math.multiplyExact((long) page, size);
        String union = searchUnion();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pattern", "%" + term.toLowerCase(Locale.ROOT) + "%")
                .addValue("limit", size)
                .addValue("offset", offset);

        List<SearchResult> content = jdbc.query(
                "SELECT entity_type, entity_id, title, reference, subtitle, email, phone "
                        + "FROM (" + union + ") platform_search "
                        + "ORDER BY entity_type, lower(title), entity_id LIMIT :limit OFFSET :offset",
                params, (rs, row) -> new SearchResult(
                        rs.getString("entity_type"), rs.getLong("entity_id"),
                        rs.getString("title"), rs.getString("reference"),
                        rs.getString("subtitle"), maskEmail(rs.getString("email")),
                        maskPhone(rs.getString("phone"))));
        Long count = jdbc.queryForObject("SELECT count(*) FROM (" + union + ") platform_search_count",
                params, Long.class);
        long total = count == null ? 0 : count;
        // Do not log the term: it may itself be a phone number or email.
        audit.logRequired("PLATFORM_GLOBAL_SEARCH", "PlatformSearch", null,
                null, null, null, null, "platform investigation",
                "queryLength=" + term.length() + ", page=" + page
                        + ", resultCount=" + content.size());
        return page(content, page, size, total);
    }

    private static String searchUnion() {
        return """
                SELECT 'CUSTOMER' entity_type, c.id entity_id,
                       COALESCE(c.full_name, 'Unnamed customer') title,
                       'C-' || CAST(c.id AS varchar) reference,
                       'Customer account' subtitle, c.email email, c.mobile_number phone
                FROM customers c
                WHERE lower(COALESCE(c.full_name, '')) LIKE :pattern
                   OR lower(COALESCE(c.email, '')) LIKE :pattern
                   OR lower(COALESCE(c.mobile_number, '')) LIKE :pattern
                   OR lower('C-' || CAST(c.id AS varchar)) LIKE :pattern
                UNION ALL
                SELECT 'MERCHANT', m.id, COALESCE(m.display_name, m.legal_name),
                       'M-' || CAST(m.id AS varchar), m.status,
                       m.contact_email, m.contact_phone
                FROM merchants m
                LEFT JOIN customers owner ON owner.id=m.owner_customer_id
                WHERE m.deleted_at IS NULL AND (
                      lower(COALESCE(m.legal_name, '')) LIKE :pattern
                   OR lower(COALESCE(m.display_name, '')) LIKE :pattern
                   OR lower(COALESCE(m.contact_email, '')) LIKE :pattern
                   OR lower(COALESCE(m.contact_phone, '')) LIKE :pattern
                   OR lower(COALESCE(owner.full_name, '')) LIKE :pattern
                   OR lower(COALESCE(owner.email, '')) LIKE :pattern
                   OR lower(COALESCE(owner.mobile_number, '')) LIKE :pattern
                   OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern)
                UNION ALL
                SELECT 'SHOP', s.id, s.display_name,
                       COALESCE(s.code, 'S-' || CAST(s.id AS varchar)),
                       'Merchant M-' || CAST(s.merchant_id AS varchar), NULL, s.support_phone
                FROM shops s JOIN merchants m ON m.id=s.merchant_id
                WHERE s.deleted_at IS NULL AND (
                      lower(COALESCE(s.display_name, '')) LIKE :pattern
                   OR lower(COALESCE(s.code, '')) LIKE :pattern
                   OR lower(COALESCE(m.display_name, m.legal_name, '')) LIKE :pattern
                   OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern
                   OR lower('M-' || CAST(s.merchant_id AS varchar)) LIKE :pattern)
                UNION ALL
                SELECT DISTINCT 'ORDER', o.id, 'Order ' || o.order_number,
                       o.order_number, COALESCE(c.full_name, 'Customer unavailable'),
                       NULL, NULL
                FROM orders o
                LEFT JOIN customers c ON c.id = o.customer_id
                LEFT JOIN payments pay ON pay.order_id = o.id
                LEFT JOIN shops s ON s.id = o.shop_id
                LEFT JOIN merchants m ON m.id = s.merchant_id
                WHERE lower(COALESCE(o.order_number, '')) LIKE :pattern
                   OR lower(COALESCE(c.full_name, '')) LIKE :pattern
                   OR lower(COALESCE(pay.transaction_id, '')) LIKE :pattern
                   OR lower(COALESCE(pay.provider_order_id, '')) LIKE :pattern
                   OR lower(COALESCE(pay.provider_payment_id, '')) LIKE :pattern
                   OR lower(COALESCE(s.display_name, '')) LIKE :pattern
                   OR lower(COALESCE(m.display_name, m.legal_name, '')) LIKE :pattern
                   OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern
                   OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern
                UNION ALL
                SELECT 'WORKER', w.id, COALESCE(w.name, 'Unnamed worker'),
                       'W-' || CAST(w.id AS varchar),
                       'Shop S-' || CAST(w.shop_id AS varchar), w.login_email, w.mobile
                FROM delivery_partners w
                JOIN shops s ON s.id=w.shop_id
                JOIN merchants m ON m.id=s.merchant_id
                WHERE w.deleted_at IS NULL AND (
                      lower(COALESCE(w.name, '')) LIKE :pattern
                   OR lower(COALESCE(w.mobile, '')) LIKE :pattern
                   OR lower(COALESCE(w.login_email, '')) LIKE :pattern
                   OR lower(COALESCE(s.display_name, '')) LIKE :pattern
                   OR lower(COALESCE(m.display_name, m.legal_name, '')) LIKE :pattern
                   OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern
                   OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern
                   OR lower('W-' || CAST(w.id AS varchar)) LIKE :pattern)
                UNION ALL
                SELECT 'PRODUCT', p.id, p.name,
                       'P-' || CAST(p.id AS varchar),
                       COALESCE(p.brand, 'Catalogue product'), NULL, NULL
                FROM products p
                WHERE lower(COALESCE(p.name, '')) LIKE :pattern
                   OR lower(COALESCE(p.brand, '')) LIKE :pattern
                   OR EXISTS (SELECT 1 FROM product_variants pv WHERE pv.product_id=p.id
                              AND (lower(COALESCE(pv.sku, '')) LIKE :pattern
                                   OR lower(COALESCE(pv.barcode, '')) LIKE :pattern))
                   OR lower('P-' || CAST(p.id AS varchar)) LIKE :pattern
                """;
    }

    @Transactional(readOnly = true)
    public DashboardSummary dashboard(LocalDateTime from, LocalDateTime to) {
        return dashboard(from, to, null);
    }

    @Transactional(readOnly = true)
    public DashboardSummary dashboard(LocalDateTime from, LocalDateTime to,
                                      DashboardFilters filters) {
        validateRange(from, to);
        MapSqlParameterSource range = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        String orderFilter = dashboardOrderFilter(filters, range);

        MarketplaceCounts counts = new MarketplaceCounts(
                count("SELECT count(*) FROM merchants WHERE deleted_at IS NULL", Map.of()),
                count("SELECT count(*) FROM merchants WHERE deleted_at IS NULL AND active = true AND status = 'ACTIVE'", Map.of()),
                count("SELECT count(*) FROM merchants WHERE deleted_at IS NULL AND status IN ('APPLICATION','PENDING_REVIEW','VERIFICATION_REQUIRED')", Map.of()),
                count("SELECT count(*) FROM merchants WHERE deleted_at IS NULL AND status = 'SUSPENDED'", Map.of()),
                count("SELECT count(*) FROM shops WHERE deleted_at IS NULL", Map.of()),
                count("""
                      SELECT count(*) FROM shops s
                      JOIN merchants m ON m.id = s.merchant_id
                      LEFT JOIN store_operations_settings ops ON ops.shop_id = s.id
                      WHERE s.deleted_at IS NULL AND s.active = true AND s.status = 'ACTIVE'
                        AND m.deleted_at IS NULL AND m.active = true AND m.status = 'ACTIVE'
                        AND (ops.id IS NULL OR ops.order_acceptance <> 'OFF'
                             OR (ops.paused_until IS NOT NULL AND ops.paused_until <= CURRENT_TIMESTAMP))
                      """, Map.of()),
                count("SELECT count(*) FROM shops WHERE deleted_at IS NULL AND status = 'PAUSED'", Map.of()),
                count("SELECT count(*) FROM shops WHERE deleted_at IS NULL AND status = 'CLOSED'", Map.of()),
                count("SELECT count(*) FROM shops WHERE deleted_at IS NULL AND status = 'SUSPENDED'", Map.of()),
                count("SELECT count(*) FROM customers", Map.of()),
                count("SELECT count(*) FROM customers WHERE active = true AND enabled = true", Map.of()),
                count("SELECT count(*) FROM customers WHERE created_at >= :from AND created_at < :to", range.getValues()),
                count("SELECT count(*) FROM delivery_partners WHERE deleted_at IS NULL", Map.of()),
                count("SELECT count(*) FROM delivery_partners WHERE deleted_at IS NULL AND active = true", Map.of()));

        Map<String, Long> statuses = new LinkedHashMap<>();
        jdbc.query("SELECT o.order_status, count(*) total FROM orders o WHERE o.order_date >= :from AND o.order_date < :to"
                        + orderFilter + " GROUP BY o.order_status",
                range, (RowCallbackHandler) rs ->
                        statuses.put(rs.getString("order_status"), rs.getLong("total")));
        statuses.put("RETURNED", count("""
                SELECT count(DISTINCT r.order_id) FROM order_returns r JOIN orders o ON o.id=r.order_id
                WHERE r.status='APPROVED' AND r.decided_at>=:from AND r.decided_at<:to
                """ + orderFilter, range.getValues()));
        statuses.put("REFUNDED", count("""
                SELECT count(DISTINCT p.order_id) FROM refunds r JOIN payments p ON p.id=r.payment_id
                JOIN orders o ON o.id=p.order_id
                WHERE r.status='SUCCEEDED' AND r.settled_at>=:from AND r.settled_at<:to
                """ + orderFilter, range.getValues()));

        Map<String, Object> money = jdbc.queryForMap("""
                SELECT COALESCE(SUM(o.total_amount - COALESCE(o.delivery_fee,0)), 0) gmv,
                       COALESCE(SUM(o.total_amount), 0) completed_sales,
                       COALESCE(SUM(o.delivery_fee), 0) delivery
                FROM orders o
                WHERE o.order_status IN ('DELIVERED','COMPLETED')
                  AND o.order_date >= :from AND o.order_date < :to
                """ + orderFilter, range);
        BigDecimal refunds = decimal(jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.amount), 0) FROM refunds r
                JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=p.order_id
                WHERE r.status = 'SUCCEEDED'
                  AND r.settled_at >= :from AND r.settled_at < :to
                """ + orderFilter, range, BigDecimal.class));
        BigDecimal cancellation = decimal(jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.cancellation_fee), 0) FROM orders o
                WHERE o.order_date >= :from AND o.order_date < :to
                """ + orderFilter, range, BigDecimal.class));
        BigDecimal commission = ledgerTotal("COMMISSION", from, to, filters)
                .add(ledgerTotal("COMMISSION_REVERSAL", from, to, filters));
        BigDecimal fees = ledgerTotal("PLATFORM_FEE", from, to, filters)
                .add(ledgerTotal("FEE_REFUND_NO_ORDERS", from, to, filters));
        BigDecimal adjustments = ledgerTotal("ADJUSTMENT", from, to, filters)
                .add(ledgerTotal("INTERVENTION_RECOVERY", from, to, filters));
        BigDecimal gmv = decimal(money.get("gmv"));
        BigDecimal completedSales = decimal(money.get("completed_sales"));
        BigDecimal delivery = decimal(money.get("delivery"));
        FinanceSummary finance = new FinanceSummary(gmv, completedSales,
                gmv, delivery, refunds, cancellation,
                commission, fees, adjustments, false);

        PresenceSnapshot snapshot = presence.snapshot();
        return new DashboardSummary(from, to, counts, Map.copyOf(statuses), finance,
                snapshot.onlineNow(), snapshot.windowSeconds(), snapshot.available());
    }

    @Transactional(readOnly = true)
    public Customer360 customer(Long id) {
        List<CustomerIdentity> identities = jdbc.query("""
                SELECT id, full_name, email, mobile_number, role, enabled, active, verified, created_at
                FROM customers WHERE id = :id
                """, Map.of("id", id), (rs, row) -> new CustomerIdentity(
                rs.getLong("id"), "C-" + rs.getLong("id"), rs.getString("full_name"),
                maskEmail(rs.getString("email")), maskPhone(rs.getString("mobile_number")),
                rs.getString("role"), bool(rs.getObject("enabled")), bool(rs.getObject("active")),
                bool(rs.getObject("verified")), time(rs.getObject("created_at"))));
        if (identities.isEmpty()) throw new ResourceNotFoundException("Customer not found");

        Map<String, Long> status = statusCounts("SELECT order_status, count(*) total FROM orders WHERE customer_id = :id GROUP BY order_status", id);
        long total = status.values().stream().mapToLong(Long::longValue).sum();
        long completed = status.getOrDefault("COMPLETED", 0L) + status.getOrDefault("DELIVERED", 0L);
        long cancelled = status.getOrDefault("CANCELLED", 0L) + status.getOrDefault("REJECTED", 0L);
        long failed = status.getOrDefault("DELIVERY_FAILED", 0L);
        long active = Math.max(0, total - completed - cancelled - failed);
        long returned = count("SELECT count(*) FROM order_returns r JOIN orders o ON o.id = r.order_id WHERE o.customer_id = :id", Map.of("id", id));
        long refundedOrders = count("SELECT count(DISTINCT o.id) FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=p.order_id WHERE o.customer_id=:id AND r.status='SUCCEEDED'", Map.of("id", id));
        Map<String, Object> finance = jdbc.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN order_status IN ('DELIVERED','COMPLETED') THEN total_amount ELSE 0 END),0) purchases,
                       COALESCE(SUM(cancellation_fee),0) cancellation
                FROM orders WHERE customer_id=:id
                """, Map.of("id", id));
        BigDecimal refunded = decimal(jdbc.queryForObject("SELECT COALESCE(SUM(r.amount),0) FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=p.order_id WHERE o.customer_id=:id AND r.status='SUCCEEDED'", Map.of("id", id), BigDecimal.class));
        List<RecentOrder> recent = jdbc.query("""
                SELECT o.id, o.order_number, o.shop_id, s.display_name, o.order_status, o.total_amount, o.order_date
                FROM orders o LEFT JOIN shops s ON s.id=o.shop_id
                WHERE o.customer_id=:id ORDER BY o.order_date DESC LIMIT 20
                """, Map.of("id", id), (rs, row) -> new RecentOrder(rs.getLong("id"), rs.getString("order_number"),
                nullableLong(rs.getObject("shop_id")), rs.getString("display_name"), rs.getString("order_status"),
                decimal(rs.getObject("total_amount")), time(rs.getObject("order_date"))));
        long reviews = count("SELECT (SELECT count(*) FROM reviews WHERE customer_id=:id) + (SELECT count(*) FROM shop_ratings WHERE customer_id=:id)", Map.of("id", id));
        long reported = count("SELECT (SELECT count(*) FROM reviews WHERE customer_id=:id AND reported_at IS NOT NULL) + (SELECT count(*) FROM shop_ratings WHERE customer_id=:id AND reported_at IS NOT NULL)", Map.of("id", id));
        return new Customer360(identities.getFirst(),
                new CustomerOrderSummary(total, completed, active, cancelled, failed, returned, refundedOrders),
                new CustomerFinance(decimal(finance.get("purchases")), refunded,
                        decimal(finance.get("cancellation"))), reviews, reported, recent);
    }

    @Transactional
    public RevealedPii revealCustomerPii(Long id, String rawField, String reason) {
        String field = rawField == null ? "" : rawField.trim().toLowerCase(Locale.ROOT);
        if (!field.equals("email") && !field.equals("phone")) {
            throw new BadRequestException("Only email or phone may be revealed");
        }
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            throw new BadRequestException("A reason of 5 to 500 characters is required");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT email, mobile_number FROM customers WHERE id=:id", Map.of("id", id));
        if (rows.isEmpty()) throw new ResourceNotFoundException("Customer not found");
        String value = (String) rows.getFirst().get(field.equals("email") ? "email" : "mobile_number");
        audit.logRequired("SENSITIVE_PII_REVEALED", "Customer", id, null, null,
                null, null, reason.trim(), "field=" + field);
        return new RevealedPii(id, field, value);
    }

    @Transactional(readOnly = true)
    public Merchant360 merchant(Long id) {
        List<MerchantIdentity> identity = jdbc.query("""
                SELECT id, legal_name, display_name, owner_customer_id, contact_email, contact_phone,
                       status, status_reason, active, is_demo, tier, created_at, updated_at
                FROM merchants WHERE id=:id AND deleted_at IS NULL
                """, Map.of("id", id), (rs, row) -> new MerchantIdentity(rs.getLong("id"), "M-" + rs.getLong("id"),
                rs.getString("legal_name"), rs.getString("display_name"),
                rs.getObject("owner_customer_id") == null ? null : "C-" + rs.getLong("owner_customer_id"),
                maskEmail(rs.getString("contact_email")), maskPhone(rs.getString("contact_phone")),
                rs.getString("status"), rs.getString("status_reason"), bool(rs.getObject("active")),
                bool(rs.getObject("is_demo")), rs.getString("tier"), time(rs.getObject("created_at")),
                time(rs.getObject("updated_at"))));
        if (identity.isEmpty()) throw new ResourceNotFoundException("Merchant not found");
        List<ShopLine> shops = shopLines("WHERE s.merchant_id=:id AND s.deleted_at IS NULL", Map.of("id", id));
        Map<String, Object> totals = commerceTotals("s.merchant_id=:id", Map.of("id", id));
        return new Merchant360(identity.getFirst(), shops,
                number(totals.get("orders")), number(totals.get("completed")), number(totals.get("cancelled")),
                decimal(totals.get("gmv")), decimal(totals.get("delivery")), decimal(totals.get("refunds")),
                decimal(totals.get("gmv")),
                ledgerTotalForMerchant(id, "COMMISSION"), ledgerTotalForMerchant(id, "PLATFORM_FEE"));
    }

    @Transactional(readOnly = true)
    public Shop360 shop(Long id) {
        List<ShopLine> lines = shopLines("WHERE s.id=:id AND s.deleted_at IS NULL", Map.of("id", id));
        if (lines.isEmpty()) throw new ResourceNotFoundException("Shop not found");
        Map<String, Object> detail = jdbc.queryForMap("""
                SELECT s.merchant_id, COALESCE(m.display_name,m.legal_name) merchant_name,
                       s.address_line,s.locality,s.pincode,s.max_delivery_radius_km,s.time_zone
                FROM shops s JOIN merchants m ON m.id=s.merchant_id WHERE s.id=:id
                """, Map.of("id", id));
        Map<String, Object> totals = commerceTotals("s.id=:id", Map.of("id", id));
        Map<String, Object> review = jdbc.queryForMap("SELECT count(*) reviews, COALESCE(avg(rating),0) rating FROM shop_ratings WHERE shop_id=:id", Map.of("id", id));
        long merchantId = number(detail.get("merchant_id"));
        return new Shop360(lines.getFirst(), merchantId, "M-" + merchantId,
                string(detail.get("merchant_name")), string(detail.get("address_line")), string(detail.get("locality")),
                string(detail.get("pincode")), decimal(detail.get("max_delivery_radius_km")), string(detail.get("time_zone")),
                decimal(totals.get("refunds")), decimal(totals.get("cancellation")), number(review.get("reviews")),
                decimal(review.get("rating")));
    }

    /**
     * Safe, read-only operational lists for the secondary control centers.
     * Resource names select a fixed SQL projection; they are never appended
     * to SQL. Only the search value, limit and offset are caller-controlled,
     * and all are bound parameters.
     */
    @Transactional(readOnly = true)
    public PageEnvelope<Map<String, Object>> resource(String rawResource,
                                                       String rawQuery,
                                                       int requestedPage,
                                                       int requestedSize,
                                                       ResourceFilters filters) {
        String resource = rawResource == null ? "" : rawResource.toLowerCase(Locale.ROOT);
        ResourceSql sql = resourceSql(resource);
        int page = Math.max(0, requestedPage);
        int size = pageSize(requestedSize);
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.length() > MAX_SEARCH_LENGTH) {
            throw new BadRequestException("Filter text is limited to 120 characters");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pattern", "%" + query + "%")
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact((long) page, size));
        String basePredicate = switch (resource) {
            case "workers" -> "w.deleted_at IS NULL";
            case "merchants" -> "m.deleted_at IS NULL";
            case "shops" -> "s.deleted_at IS NULL";
            case "security" -> "(a.action LIKE '%LOGIN%' OR a.action LIKE '%ACTIVATION%' OR a.action LIKE '%PASSWORD%' OR a.action LIKE '%SUSPEND%' OR a.action LIKE '%REACTIVAT%' OR a.action LIKE '%ROLE%' OR a.action LIKE '%PII%' OR a.action LIKE '%ACCESS%')";
            default -> "";
        };
        List<String> predicates = new java.util.ArrayList<>();
        if (!basePredicate.isEmpty()) predicates.add(basePredicate);
        if (!query.isEmpty()) predicates.add("(" + sql.predicate() + ")");
        ResourceFilters safeFilters = filters == null
                ? new ResourceFilters(null, null, null, null, null,
                        null, null, null, null, null, null)
                : filters;
        addTextFilter(predicates, params, statusColumn(resource), "status",
                safeFilters.status());
        addIdFilter(predicates, params, merchantColumn(resource), "merchantId",
                safeFilters.merchantId());
        addIdFilter(predicates, params, shopColumn(resource), "shopId",
                safeFilters.shopId());
        addIdFilter(predicates, params, customerColumn(resource), "customerId",
                safeFilters.customerId());
        addIdFilter(predicates, params, workerColumn(resource), "workerId",
                safeFilters.workerId());
        addTextFilter(predicates, params, paymentStatusColumn(resource), "paymentStatus",
                safeFilters.paymentStatus());
        addTextFilter(predicates, params, paymentMethodColumn(resource), "paymentMethod",
                safeFilters.paymentMethod());
        addCategoryFilter(predicates, params, resource, safeFilters.category());
        addStockFilter(predicates, resource, safeFilters.stockStatus());
        String dateColumn = dateColumn(resource);
        if (safeFilters.from() != null || safeFilters.to() != null) {
            if (safeFilters.from() == null || safeFilters.to() == null) {
                throw new BadRequestException("Both from and to dates are required");
            }
            validateRange(safeFilters.from(), safeFilters.to());
            if (dateColumn == null) {
                throw new BadRequestException("Date filtering is not available for " + resource);
            }
            predicates.add(dateColumn + ">=:resourceFrom AND " + dateColumn + "<:resourceTo");
            params.addValue("resourceFrom", safeFilters.from());
            params.addValue("resourceTo", safeFilters.to());
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        List<Map<String, Object>> content = jdbc.query(
                sql.select() + " " + sql.from() + where + " " + sql.orderBy()
                        + " LIMIT :limit OFFSET :offset",
                params, (rs, row) -> safeRow(rs));
        if (resource.equals("workers")) {
            content.forEach(row -> row.put("maskedPhone", maskPhone(string(row.get("maskedPhone")))));
        } else if (resource.equals("customers") || resource.equals("merchants")) {
            content.forEach(row -> {
                row.put("maskedPhone", maskPhone(string(row.get("maskedPhone"))));
                row.put("maskedEmail", maskEmail(string(row.get("maskedEmail"))));
            });
        } else if (resource.equals("audit") || resource.equals("security")) {
            content.forEach(row -> row.put("actorEmail", maskEmail(string(row.get("actorEmail")))));
        }
        Long total = jdbc.queryForObject("SELECT count(*) " + sql.from() + where, params, Long.class);
        return page(content, page, size, total == null ? 0 : total);
    }

    private static void addTextFilter(List<String> predicates, MapSqlParameterSource params,
                                      String column, String parameter, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return;
        if (column == null) {
            throw new BadRequestException(parameter + " filtering is not available for this resource");
        }
        String value = rawValue.trim().toUpperCase(Locale.ROOT);
        if (value.length() > 40) throw new BadRequestException(parameter + " is too long");
        predicates.add(column + "=:" + parameter);
        params.addValue(parameter, value);
    }

    private static void addIdFilter(List<String> predicates, MapSqlParameterSource params,
                                    String column, String parameter, Long value) {
        if (value == null) return;
        if (value <= 0) throw new BadRequestException(parameter + " must be positive");
        if (column == null) {
            throw new BadRequestException(parameter + " filtering is not available for this resource");
        }
        predicates.add(column + "=:" + parameter);
        params.addValue(parameter, value);
    }

    private static String statusColumn(String resource) {
        return switch (resource) {
            case "customers" -> "(CASE WHEN c.enabled=true AND c.active=true THEN 'ACTIVE' WHEN c.enabled=false THEN 'DISABLED' ELSE 'INACTIVE' END)";
            case "merchants" -> "m.status";
            case "shops" -> "s.status";
            case "orders" -> "o.order_status";
            case "workers" -> "(CASE WHEN w.active=true THEN 'ACTIVE' ELSE 'INACTIVE' END)";
            case "payments" -> "p.payment_status";
            case "refunds", "returns" -> "r.status";
            default -> null;
        };
    }

    private static String merchantColumn(String resource) {
        return switch (resource) {
            case "merchants" -> "m.id";
            case "shops", "orders", "workers", "products", "payments", "refunds",
                    "shop-reviews" -> "m.id";
            case "audit", "security" -> "a.merchant_id";
            default -> null;
        };
    }

    private static String shopColumn(String resource) {
        return switch (resource) {
            case "shops", "orders", "workers", "products", "payments", "refunds",
                    "returns", "shop-reviews" -> "s.id";
            case "audit", "security" -> "a.shop_id";
            default -> null;
        };
    }

    private static String customerColumn(String resource) {
        return switch (resource) {
            case "customers" -> "c.id";
            case "orders", "payments", "refunds" -> "o.customer_id";
            case "returns", "reviews", "shop-reviews" -> "r.customer_id";
            default -> null;
        };
    }

    private static String workerColumn(String resource) {
        return switch (resource) {
            case "workers" -> "w.id";
            case "orders" -> "o.assigned_worker_partner_id";
            default -> null;
        };
    }

    private static String paymentStatusColumn(String resource) {
        return switch (resource) {
            case "orders" -> "o.payment_status";
            case "payments", "refunds" -> "p.payment_status";
            default -> null;
        };
    }

    private static String paymentMethodColumn(String resource) {
        return switch (resource) {
            case "orders" -> "(SELECT p2.payment_method FROM payments p2 WHERE p2.order_id=o.id ORDER BY p2.id DESC LIMIT 1)";
            case "payments", "refunds" -> "p.payment_method";
            default -> null;
        };
    }

    private static String dateColumn(String resource) {
        return switch (resource) {
            case "customers" -> "c.created_at";
            case "merchants" -> "m.created_at";
            case "shops" -> "s.created_at";
            case "orders" -> "o.order_date";
            case "payments" -> "p.payment_date";
            case "refunds" -> "COALESCE(r.settled_at,r.requested_at,r.created_at)";
            case "returns" -> "r.requested_at";
            case "reviews" -> "r.review_date";
            case "shop-reviews" -> "r.created_at";
            case "audit", "security" -> "a.occurred_at";
            default -> null;
        };
    }

    private static void addCategoryFilter(List<String> predicates,
                                          MapSqlParameterSource params,
                                          String resource, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return;
        if (!resource.equals("products")) {
            throw new BadRequestException(
                    "category filtering is not available for this resource");
        }
        String value = rawValue.trim().toUpperCase(Locale.ROOT);
        if (value.length() > 120) throw new BadRequestException("category is too long");
        predicates.add("upper(c.name)=:category");
        params.addValue("category", value);
    }

    private static void addStockFilter(List<String> predicates,
                                       String resource, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return;
        if (!resource.equals("products")) {
            throw new BadRequestException(
                    "stockStatus filtering is not available for this resource");
        }
        String value = rawValue.trim().toUpperCase(Locale.ROOT);
        if (value.equals("IN_STOCK")) {
            predicates.add("COALESCE(i.stock,0)-COALESCE(i.reserved_stock,0)>0");
        } else if (value.equals("OUT_OF_STOCK")) {
            predicates.add("COALESCE(i.stock,0)-COALESCE(i.reserved_stock,0)<=0");
        } else {
            throw new BadRequestException("stockStatus must be IN_STOCK or OUT_OF_STOCK");
        }
    }

    private static ResourceSql resourceSql(String resource) {
        return switch (resource) {
            case "customers" -> new ResourceSql("""
                    SELECT c.id id,c.full_name name,c.email "maskedEmail",c.mobile_number "maskedPhone",
                           c.enabled enabled,c.active active,c.verified verified,c.created_at "createdAt",
                           (SELECT count(*) FROM orders o WHERE o.customer_id=c.id) orders
                    """, "FROM customers c",
                    "lower(COALESCE(c.full_name,'')) LIKE :pattern OR lower(COALESCE(c.email,'')) LIKE :pattern OR lower(COALESCE(c.mobile_number,'')) LIKE :pattern OR lower('C-' || CAST(c.id AS varchar)) LIKE :pattern",
                    "ORDER BY c.id DESC");
            case "merchants" -> new ResourceSql("""
                    SELECT m.id id,COALESCE(m.display_name,m.legal_name) name,m.legal_name "legalName",
                           m.contact_email "maskedEmail",m.contact_phone "maskedPhone",m.status status,
                           m.status_reason "statusReason",m.active active,m.created_at "createdAt",
                           (SELECT count(*) FROM shops s WHERE s.merchant_id=m.id AND s.deleted_at IS NULL) shops
                    """, "FROM merchants m",
                    "m.deleted_at IS NULL AND (lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower(COALESCE(m.contact_email,'')) LIKE :pattern OR lower(COALESCE(m.contact_phone,'')) LIKE :pattern OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern)",
                    "ORDER BY m.id DESC");
            case "shops" -> new ResourceSql("""
                    SELECT s.id id,s.display_name name,s.code code,s.status status,s.status_reason "statusReason",
                           s.active active,s.verification_level verification,s.city city,s.state state,
                           m.id "merchantId",COALESCE(m.display_name,m.legal_name) merchant
                    """, "FROM shops s JOIN merchants m ON m.id=s.merchant_id",
                    "s.deleted_at IS NULL AND (lower(COALESCE(s.display_name,'')) LIKE :pattern OR lower(COALESCE(s.code,'')) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern)",
                    "ORDER BY s.id DESC");
            case "orders" -> new ResourceSql("""
                    SELECT o.id id,o.order_number "orderNumber",o.order_date "orderedAt",
                           o.order_status status,o.payment_status "paymentStatus",o.total_amount total,
                           o.delivery_fee "deliveryCharge",c.full_name customer,
                           s.id "shopId",s.display_name shop,m.id "merchantId",
                           COALESCE(m.display_name,m.legal_name) merchant
                    """, "FROM orders o LEFT JOIN customers c ON c.id=o.customer_id JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id",
                    "lower(COALESCE(o.order_number,'')) LIKE :pattern OR lower('O-' || CAST(o.id AS varchar)) LIKE :pattern OR lower(COALESCE(c.full_name,'')) LIKE :pattern OR lower(COALESCE(s.display_name,'')) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR EXISTS (SELECT 1 FROM payments pay WHERE pay.order_id=o.id AND (lower(COALESCE(pay.transaction_id,'')) LIKE :pattern OR lower(COALESCE(pay.provider_order_id,'')) LIKE :pattern OR lower(COALESCE(pay.provider_payment_id,'')) LIKE :pattern))",
                    "ORDER BY o.order_date DESC");
            case "workers" -> new ResourceSql("""
                    SELECT w.id id,w.name name,w.mobile "maskedPhone",w.available available,w.active active,
                           w.vehicle_type "vehicleType",w.vehicle_number "vehicleNumber",
                           s.id "shopId",s.display_name shop,m.id "merchantId",
                           COALESCE(m.display_name,m.legal_name) merchant,
                           (SELECT count(*) FROM orders o WHERE o.assigned_worker_partner_id=w.id AND o.order_status IN ('READY_TO_DISPATCH','OUT_FOR_DELIVERY')) "activeOrders",
                           (SELECT count(*) FROM orders o WHERE o.assigned_worker_partner_id=w.id AND o.order_status IN ('DELIVERED','COMPLETED')) "completedDeliveries"
                    """, "FROM delivery_partners w JOIN shops s ON s.id=w.shop_id JOIN merchants m ON m.id=s.merchant_id",
                    "lower(COALESCE(w.name,'')) LIKE :pattern OR lower(COALESCE(w.mobile,'')) LIKE :pattern OR lower('W-' || CAST(w.id AS varchar)) LIKE :pattern OR lower(COALESCE(s.display_name,'')) LIKE :pattern OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern",
                    "ORDER BY w.id DESC");
            case "products" -> new ResourceSql("""
                    SELECT spv.id id,p.id "productId",p.name product,p.brand brand,c.name category,
                           pv.id "variantId",pv.sku sku,pv.barcode barcode,
                           spv.selling_price "sellingPrice",spv.mrp mrp,spv.available available,spv.active active,
                           COALESCE(i.stock,0) stock,COALESCE(i.reserved_stock,0) "reservedStock",
                           s.id "shopId",s.display_name shop,m.id "merchantId"
                    """, "FROM shop_product_variants spv JOIN product_variants pv ON pv.id=spv.product_variant_id JOIN products p ON p.id=pv.product_id LEFT JOIN categories c ON c.id=p.category_id JOIN shops s ON s.id=spv.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN inventory i ON i.shop_id=s.id AND i.product_variant_id=pv.id",
                    "lower(COALESCE(p.name,'')) LIKE :pattern OR lower(COALESCE(p.brand,'')) LIKE :pattern OR lower('P-' || CAST(p.id AS varchar)) LIKE :pattern OR lower(COALESCE(pv.sku,'')) LIKE :pattern OR lower(COALESCE(pv.barcode,'')) LIKE :pattern OR lower(COALESCE(s.display_name,'')) LIKE :pattern",
                    "ORDER BY p.name,spv.id");
            case "payments" -> new ResourceSql("""
                    SELECT p.id id,o.id "orderId",o.order_number "orderNumber",p.amount amount,
                           p.payment_method method,p.payment_status status,p.provider provider,
                           p.transaction_id "transactionReference",p.provider_order_id "providerOrderReference",
                           p.payment_date "createdAt",s.id "shopId",s.display_name shop,
                           m.id "merchantId",c.full_name customer
                    """, "FROM payments p JOIN orders o ON o.id=p.order_id JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN customers c ON c.id=o.customer_id",
                    "lower(COALESCE(o.order_number,'')) LIKE :pattern OR lower(COALESCE(p.transaction_id,'')) LIKE :pattern OR lower(COALESCE(p.provider_order_id,'')) LIKE :pattern OR lower(COALESCE(s.display_name,'')) LIKE :pattern",
                    "ORDER BY p.payment_date DESC,p.id DESC");
            case "refunds" -> new ResourceSql("""
                    SELECT r.id id,r.refund_id "refundReference",r.amount amount,r.status status,
                           r.channel channel,r.reason reason,r.failure_reason "failureReason",
                           r.requested_at "requestedAt",r.settled_at "settledAt",
                           p.id "paymentId",o.id "orderId",o.order_number "orderNumber",
                           s.id "shopId",s.display_name shop,m.id "merchantId",c.full_name customer
                    """, "FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=p.order_id JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN customers c ON c.id=o.customer_id",
                    "lower(COALESCE(r.refund_id,'')) LIKE :pattern OR lower(COALESCE(o.order_number,'')) LIKE :pattern OR lower(COALESCE(s.display_name,'')) LIKE :pattern",
                    "ORDER BY r.created_at DESC,r.id DESC");
            case "returns" -> new ResourceSql("""
                    SELECT r.id id,r.status status,r.reason reason,r.decision_note "decisionNote",
                           r.refund_amount "refundAmount",r.refund_id "refundReference",
                           r.requested_at "requestedAt",r.decided_at "decidedAt",
                           o.id "orderId",o.order_number "orderNumber",s.id "shopId",s.display_name shop,
                           c.full_name customer
                    """, "FROM order_returns r JOIN orders o ON o.id=r.order_id JOIN shops s ON s.id=o.shop_id LEFT JOIN customers c ON c.id=r.customer_id",
                    "lower(COALESCE(o.order_number,'')) LIKE :pattern OR lower(COALESCE(s.display_name,'')) LIKE :pattern OR lower(COALESCE(c.full_name,'')) LIKE :pattern",
                    "ORDER BY r.requested_at DESC,r.id DESC");
            case "reviews" -> new ResourceSql("""
                    SELECT r.id id,'PRODUCT' "reviewType",r.rating rating,r.comment review,
                           r.review_date "createdAt",r.reported_at "reportedAt",r.hidden_at "hiddenAt",
                           p.id "productId",p.name product,c.full_name customer,r.responding_shop_id "shopId"
                    """, "FROM reviews r JOIN products p ON p.id=r.product_id LEFT JOIN customers c ON c.id=r.customer_id",
                    "lower(COALESCE(p.name,'')) LIKE :pattern OR lower(COALESCE(c.full_name,'')) LIKE :pattern OR lower(COALESCE(r.comment,'')) LIKE :pattern",
                    "ORDER BY r.review_date DESC,r.id DESC");
            case "shop-reviews" -> new ResourceSql("""
                    SELECT r.id id,'SHOP' "reviewType",r.rating rating,r.comment review,
                           r.created_at "createdAt",r.reported_at "reportedAt",r.hidden_at "hiddenAt",
                           s.id "shopId",s.display_name shop,m.id "merchantId",
                           COALESCE(m.display_name,m.legal_name) merchant,c.full_name customer
                    """, "FROM shop_ratings r JOIN shops s ON s.id=r.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN customers c ON c.id=r.customer_id",
                    "lower(COALESCE(s.display_name,'')) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower(COALESCE(c.full_name,'')) LIKE :pattern OR lower(COALESCE(r.comment,'')) LIKE :pattern",
                    "ORDER BY r.created_at DESC,r.id DESC");
            case "audit" -> new ResourceSql("""
                    SELECT a.id id,a.occurred_at "occurredAt",a.actor_customer_id "actorUserId",
                           a.actor_email "actorEmail",a.actor_role "actorRole",a.action action,
                           a.entity_type "targetType",a.entity_id "targetId",a.merchant_id "merchantId",
                           a.shop_id "shopId",a.previous_state "previousState",a.new_state "newState",
                           a.reason reason,a.request_id "requestId",a.details details
                    """, "FROM audit_logs a",
                    "lower(COALESCE(a.action,'')) LIKE :pattern OR lower(COALESCE(a.entity_type,'')) LIKE :pattern OR lower(COALESCE(a.actor_email,'')) LIKE :pattern OR lower(COALESCE(a.request_id,'')) LIKE :pattern",
                    "ORDER BY a.occurred_at DESC,a.id DESC");
            case "security" -> new ResourceSql("""
                    SELECT a.id id,a.occurred_at "occurredAt",a.actor_customer_id "actorUserId",
                           a.actor_email "actorEmail",a.actor_role "actorRole",a.action action,
                           a.entity_type "targetType",a.entity_id "targetId",a.merchant_id "merchantId",
                           a.shop_id "shopId",a.previous_state "previousState",a.new_state "newState",
                           a.reason reason,a.request_id "requestId",a.details details
                    """, "FROM audit_logs a",
                    "lower(COALESCE(a.action,'')) LIKE :pattern OR lower(COALESCE(a.entity_type,'')) LIKE :pattern OR lower(COALESCE(a.actor_email,'')) LIKE :pattern OR lower(COALESCE(a.request_id,'')) LIKE :pattern",
                    "ORDER BY a.occurred_at DESC,a.id DESC");
            default -> throw new BadRequestException("Unknown platform resource: " + resource);
        };
    }

    @Transactional(readOnly = true)
    public Map<String, Object> orderDetail(Long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT o.id id,o.order_number "orderNumber",o.order_date "orderedAt",
                       o.order_status status,o.payment_status "paymentStatus",o.total_amount total,
                       o.discount_amount discount,o.delivery_fee "deliveryCharge",
                       o.cancellation_fee "cancellationFee",o.ended_by "endedBy",
                       o.ended_reason "endedReason",o.ended_at "endedAt",
                       c.id "customerId",c.full_name customer,c.email "customerEmail",c.mobile_number "customerPhone",
                       s.id "shopId",s.display_name shop,m.id "merchantId",COALESCE(m.display_name,m.legal_name) merchant,
                       o.assigned_worker_partner_id "workerId"
                FROM orders o LEFT JOIN customers c ON c.id=o.customer_id
                JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id
                WHERE o.id=:id
                """, Map.of("id", id), (rs, row) -> safeRow(rs));
        if (rows.isEmpty()) throw new ResourceNotFoundException("Order not found");
        Map<String, Object> detail = new LinkedHashMap<>(rows.getFirst());
        detail.put("customerEmail", maskEmail(string(detail.get("customerEmail"))));
        detail.put("customerPhone", maskPhone(string(detail.get("customerPhone"))));
        detail.put("items", jdbc.query("""
                SELECT oi.id id,p.name product,pv.id "variantId",pv.sku sku,pv.barcode barcode,
                       oi.quantity quantity,oi.price price
                FROM order_items oi JOIN product_variants pv ON pv.id=oi.product_variant_id
                JOIN products p ON p.id=pv.product_id WHERE oi.order_id=:id ORDER BY oi.id
                """, Map.of("id", id), (rs, row) -> safeRow(rs)));
        detail.put("payment", jdbc.query("""
                SELECT id,amount,payment_method "paymentMethod",payment_status "paymentStatus",
                       provider,transaction_id "transactionReference",provider_order_id "providerOrderReference",
                       refund_amount "refundAmount",refunded_at "refundedAt"
                FROM payments WHERE order_id=:id
                """, Map.of("id", id), (rs, row) -> safeRow(rs)).stream().findFirst().orElse(null));
        detail.put("timeline", jdbc.query("""
                SELECT id,occurred_at "timestamp",actor_role "actorRole",actor_customer_id "actorId",
                       previous_state "previousStatus",new_state "newStatus",reason,action,request_id "requestId"
                FROM audit_logs WHERE entity_type='Order' AND entity_id=:id ORDER BY occurred_at,id
                """, Map.of("id", id), (rs, row) -> safeRow(rs)));
        return detail;
    }

    private static Map<String, Object> safeRow(ResultSet rs) throws SQLException {
        ResultSetMetaData metadata = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            row.put(metadata.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private List<ShopLine> shopLines(String where, Map<String, ?> params) {
        return jdbc.query("""
                SELECT s.id,s.code,s.display_name,s.status,s.status_reason,s.active,s.verification_level,
                       s.city,s.state,COALESCE(ops.order_acceptance,'AUTO') acceptance,
                       (SELECT count(*) FROM delivery_partners w WHERE w.shop_id=s.id AND w.deleted_at IS NULL) workers,
                       (SELECT count(*) FROM shop_product_variants spv WHERE spv.shop_id=s.id AND spv.active=true) products,
                       (SELECT count(*) FROM orders o WHERE o.shop_id=s.id) orders,
                       (SELECT COALESCE(sum(o.total_amount-COALESCE(o.delivery_fee,0)),0)
                          FROM orders o WHERE o.shop_id=s.id
                           AND o.order_status IN ('DELIVERED','COMPLETED')) gmv
                FROM shops s LEFT JOIN store_operations_settings ops ON ops.shop_id=s.id
                """ + where + " ORDER BY s.id", params, (rs, row) -> new ShopLine(
                rs.getLong("id"), "S-" + rs.getLong("id"), rs.getString("code"), rs.getString("display_name"),
                rs.getString("status"), rs.getString("status_reason"), bool(rs.getObject("active")),
                rs.getString("verification_level"), rs.getString("city"), rs.getString("state"),
                rs.getString("acceptance"), rs.getLong("workers"), rs.getLong("products"), rs.getLong("orders"),
                decimal(rs.getObject("gmv"))));
    }

    private Map<String, Object> commerceTotals(String predicate, Map<String, ?> params) {
        return jdbc.queryForMap("""
                SELECT count(o.id) orders,
                       COALESCE(sum(CASE WHEN o.order_status IN ('DELIVERED','COMPLETED') THEN 1 ELSE 0 END),0) completed,
                       COALESCE(sum(CASE WHEN o.order_status IN ('CANCELLED','REJECTED') THEN 1 ELSE 0 END),0) cancelled,
                       COALESCE(sum(CASE WHEN o.order_status IN ('DELIVERED','COMPLETED')
                                         THEN o.total_amount-COALESCE(o.delivery_fee,0) ELSE 0 END),0) gmv,
                       COALESCE(sum(CASE WHEN o.order_status IN ('DELIVERED','COMPLETED') THEN o.delivery_fee ELSE 0 END),0) delivery,
                       COALESCE(sum(o.cancellation_fee),0) cancellation,
                       COALESCE((SELECT sum(r.amount) FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders ro ON ro.id=p.order_id JOIN shops rs ON rs.id=ro.shop_id WHERE r.status='SUCCEEDED' AND
                """ + predicate.replace("s.", "rs.") + "),0) refunds FROM orders o JOIN shops s ON s.id=o.shop_id WHERE " + predicate,
                params);
    }

    private static String dashboardOrderFilter(DashboardFilters filters,
                                               MapSqlParameterSource params) {
        if (filters == null) return "";
        List<String> predicates = new java.util.ArrayList<>();
        if (filters.merchantId() != null) {
            if (filters.merchantId() <= 0) {
                throw new BadRequestException("merchantId must be positive");
            }
            predicates.add("o.shop_id IN (SELECT id FROM shops WHERE merchant_id=:financeMerchantId)");
            params.addValue("financeMerchantId", filters.merchantId());
        }
        addIdFilter(predicates, params, "o.shop_id", "financeShopId", filters.shopId());
        addTextFilter(predicates, params, "o.order_status", "financeOrderStatus",
                filters.orderStatus());
        addTextFilter(predicates, params, "o.payment_status", "financePaymentStatus",
                filters.paymentStatus());
        addTextFilter(predicates, params,
                "(SELECT p.payment_method FROM payments p WHERE p.order_id=o.id ORDER BY p.id DESC LIMIT 1)",
                "financePaymentMethod", filters.paymentMethod());
        return predicates.isEmpty() ? "" : " AND " + String.join(" AND ", predicates);
    }

    private BigDecimal ledgerTotal(String type, LocalDateTime from, LocalDateTime to,
                                   DashboardFilters filters) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("type", type).addValue("from", from).addValue("to", to);
        List<String> predicates = new java.util.ArrayList<>();
        if (filters != null && filters.merchantId() != null) {
            addIdFilter(predicates, params, "l.merchant_id", "financeMerchantId",
                    filters.merchantId());
        }
        boolean needsOrder = filters != null && (filters.shopId() != null
                || (filters.orderStatus() != null && !filters.orderStatus().isBlank())
                || (filters.paymentStatus() != null && !filters.paymentStatus().isBlank())
                || (filters.paymentMethod() != null && !filters.paymentMethod().isBlank()));
        if (needsOrder) {
            MapSqlParameterSource orderParams = new MapSqlParameterSource();
            DashboardFilters orderOnly = new DashboardFilters(null, filters.shopId(),
                    filters.orderStatus(), filters.paymentStatus(), filters.paymentMethod());
            String orderFilter = dashboardOrderFilter(orderOnly, orderParams);
            orderParams.getValues().forEach((key, value) -> params.addValue(key, value));
            predicates.add("EXISTS (SELECT 1 FROM orders o WHERE o.id=l.order_id"
                    + orderFilter + ")");
        }
        String extra = predicates.isEmpty() ? "" : " AND " + String.join(" AND ", predicates);
        return decimal(jdbc.queryForObject("""
                SELECT COALESCE(sum(l.amount),0) FROM merchant_ledger_entry l
                WHERE l.entry_type=:type AND l.created_at>=:from AND l.created_at<:to
                """ + extra, params, BigDecimal.class));
    }

    private BigDecimal ledgerTotalForMerchant(Long merchantId, String type) {
        return decimal(jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM merchant_ledger_entry WHERE merchant_id=:merchantId AND entry_type=:type",
                Map.of("merchantId", merchantId, "type", type), BigDecimal.class));
    }

    private Map<String, Long> statusCounts(String sql, Long id) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("id", id), (RowCallbackHandler) rs ->
                result.put(rs.getString(1), rs.getLong(2)));
        return result;
    }

    private long count(String sql, Map<String, ?> params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static <T> PageEnvelope<T> page(List<T> content, int page, int size, long total) {
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageEnvelope<>(List.copyOf(content), page, size, total, pages);
    }

    private static int pageSize(int size) { return Math.max(1, Math.min(size, MAX_PAGE_SIZE)); }

    private static void validateRange(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) throw new BadRequestException("Invalid date range");
        if (from.isBefore(to.minusDays(730))) throw new BadRequestException("Date range cannot exceed 730 days");
    }

    private static Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime dt) return dt;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        return null;
    }
    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal result = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
        return result.setScale(2, RoundingMode.HALF_UP);
    }
    static String maskEmail(String value) {
        if (value == null || value.isBlank()) return null;
        int at = value.indexOf('@');
        return at <= 0 ? "***" : value.substring(0, 1) + "***" + value.substring(at);
    }
    static String maskPhone(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) return "****";
        return "*".repeat(Math.min(8, digits.length() - 4)) + digits.substring(digits.length() - 4);
    }
}
