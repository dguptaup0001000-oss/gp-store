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
import com.gpstore.platform.PlatformProfiles.AuditEntry;
import com.gpstore.platform.PlatformProfiles.MerchantActivity;
import com.gpstore.platform.PlatformProfiles.MerchantCommerce;
import com.gpstore.platform.PlatformProfiles.MerchantReputation;
import com.gpstore.platform.PlatformProfiles.MerchantWorkforce;
import com.gpstore.platform.PlatformProfiles.WorkerLine;
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

    private final com.gpstore.service.CustomerService customers;

    public PlatformControlTowerService(NamedParameterJdbcTemplate jdbc,
                                       PresenceTracker presence,
                                       AuditLogService audit,
                                       com.gpstore.service.CustomerService customers) {
        this.jdbc = jdbc;
        this.presence = presence;
        this.audit = audit;
        this.customers = customers;
    }

    public record PageEnvelope<T>(List<T> content, int page, int size,
                                  long totalElements, int totalPages) {}

    public record SearchResult(String entityType, Long entityId, String title,
                               String reference, String subtitle,
                               /* Legacy JSON field names retained for old app builds. */
                               String maskedEmail, String maskedPhone) {
        @com.fasterxml.jackson.annotation.JsonProperty("email")
        public String email() { return maskedEmail; }
        @com.fasterxml.jackson.annotation.JsonProperty("phone")
        public String phone() { return maskedPhone; }
    }

    public record MarketplaceCounts(long totalMerchants, long activeMerchants,
                                    long pendingMerchants, long suspendedMerchants,
                                    long totalShops, long acceptingOrdersShops,
                                    long pausedShops, long closedShops,
                                    long suspendedShops, long totalCustomers,
                                    long activeCustomerAccounts, long newCustomers,
                                    long totalWorkers, long activeWorkers) {}

    /**
     * How much of the marketplace is online, over-the-counter and services.
     *
     * <p>THE SUPER ADMIN COULD NOT SEE TWO THIRDS OF IT. Every count on this
     * dashboard is derived from orders, and a Visit-to-Buy listing and a
     * service produce no order by design. So a platform operator looking at
     * this screen saw a marketplace of online kiranas and had no way to tell
     * whether the jewellers and barbers they onboarded had listed anything at
     * all.
     *
     * <p>LISTINGS AND INTEREST, NOT REVENUE. These are counts of what exists
     * and of what customers did in the app. GP-STORE does not know what an
     * offline listing earned, and this record deliberately has nowhere to put
     * such a number - a platform that bills against trade it did not witness
     * is charging for a guess.
     *
     * @param engagementNote travels with the numbers rather than living in a
     *                       screen's copy, so a second client cannot show
     *                       them without it.
     */
    public record CommerceModeMix(long onlineListings, long visitToBuyListings,
                                  long serviceListings,
                                  long shopsSellingOnline, long shopsWithVisitToBuy,
                                  long shopsWithServices,
                                  Map<String, Long> engagementByMode,
                                  String engagementNote) {}

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
                                   CommerceModeMix commerceModes,
                                   Map<String, Long> orderStatuses,
                                   FinanceSummary finance,
                                   Integer recentlyActiveAuthenticatedAccounts,
                                   Integer presenceWindowSeconds,
                                   boolean presenceAvailable) {}

    /**
     * @param profileImageUrl the picture the customer uploaded, or NULL when
     *     they never uploaded one. The console draws initials in that case.
     *     There is no placeholder avatar and no generated face: a photo on a
     *     profile is a claim that this is what this person looks like, and an
     *     invented one would be a lie told to an operator who is about to act
     *     on the account.
     */
    public record CustomerIdentity(Long id, String customerRef, String name,
                                   String maskedEmail, String maskedPhone,
                                   String role, Boolean enabled, Boolean active,
                                   Boolean verified, LocalDateTime createdAt,
                                   String profileImageUrl) {
        @com.fasterxml.jackson.annotation.JsonProperty("email")
        public String email() { return maskedEmail; }
        @com.fasterxml.jackson.annotation.JsonProperty("phone")
        public String phone() { return maskedPhone; }
    }

    public record CustomerOrderSummary(long total, long completed, long active,
                                       long cancelled, long failed, long returned,
                                       long refunded) {}

    /**
     * @param averageCompletedOrder what a typical completed order came to, or
     *     ZERO when they have never completed one. COMPUTED FROM COMPLETED
     *     ORDERS ONLY, like the lifetime figure beside it: averaging in a
     *     cancelled basket would describe spending that never happened.
     * @param lastOrderAt when they last ordered anything, whatever became of
     *     it. Null for a customer who has never ordered - the console prints
     *     a dash rather than inventing a date.
     */
    public record CustomerFinance(BigDecimal completedPurchaseValue,
                                  BigDecimal refunds, BigDecimal cancellationFees,
                                  BigDecimal averageCompletedOrder,
                                  LocalDateTime lastOrderAt) {}

    public record RecentOrder(Long id, String orderNumber, Long shopId,
                              String shopName, String status, BigDecimal total,
                              LocalDateTime orderedAt) {}

    /**
     * A customer 360, composed of the existing profile plus what was missing.
     *
     * <p>{@link #core} is the unchanged {@link Customer360}, so the order
     * summary and finance figures have one definition rather than two.
     */
    public record CustomerProfile(Customer360 core,
                                  List<PlatformProfiles.AddressLine> addresses,
                                  List<PlatformProfiles.ShopAffinity> shops,
                                  List<PlatformProfiles.CategoryAffinity> categories,
                                  List<PlatformProfiles.PaymentLine> payments,
                                  List<PlatformProfiles.RefundLine> refunds,
                                  List<PlatformProfiles.ReviewLine> reviews,
                                  List<PlatformProfiles.ReturnLine> returns,
                                  PlatformProfiles.CustomerActivity activity,
                                  List<PlatformProfiles.AuditEntry> security) {}

    public record Customer360(CustomerIdentity identity, CustomerOrderSummary orders,
                              CustomerFinance finance, long reviews,
                              long reportedReviews, List<RecentOrder> recentOrders) {}

    public record RevealedPii(Long customerId, String field, String value) {}

    public record MerchantIdentity(Long id, String merchantRef, String legalName,
                                   String displayName, String ownerCustomerRef,
                                   String maskedEmail, String maskedPhone,
                                   String status, String statusReason, Boolean active,
                                   Boolean demo, String tier, LocalDateTime createdAt,
                                   LocalDateTime updatedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty("email")
        public String email() { return maskedEmail; }
        @com.fasterxml.jackson.annotation.JsonProperty("phone")
        public String phone() { return maskedPhone; }
    }

    public record ShopLine(Long id, String shopRef, String code, String name,
                           String status, String statusReason, Boolean active,
                           String verification, String city, String state,
                           String orderAcceptance, long workers, long products,
                           long orders, BigDecimal gmv) {}

    /**
     * A merchant 360, composed of the existing profile plus what was missing.
     *
     * <p>{@link #core} is the unchanged {@link Merchant360}, so there is one
     * definition of this merchant's identity, shops and totals rather than a
     * second that drifts from it.
     */
    public record MerchantProfile(Merchant360 core,
                                  PlatformProfiles.MerchantCommerce commerce,
                                  PlatformProfiles.MerchantWorkforce workforce,
                                  PlatformProfiles.MerchantReputation reputation,
                                  PlatformProfiles.MerchantActivity activity,
                                  List<PlatformProfiles.AuditEntry> security,
                                  LocalDateTime from, LocalDateTime to) {}

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

    /**
     * One merchant as the directory lists them.
     *
     * <p>CARRIES THE SHOP COUNT AND THE OWNER because those are what an
     * operator reads to tell two Deepaks apart before tapping either. Both
     * come from the same statement as the row - a count per result would be
     * the N+1 the marketplace work spent so long removing.
     */
    public record MerchantHit(Long id, String merchantRef, String displayName,
                              String legalName, String ownerName, String email,
                              String phone, String status, boolean active,
                              long shopCount, LocalDateTime createdAt) {}

    /** One customer as the directory lists them, with enough to identify them. */
    public record CustomerHit(Long id, String customerRef, String name, String email,
                              String phone, boolean active, long orderCount,
                              LocalDateTime createdAt, LocalDateTime lastOrderAt) {}

    /**
     * The merchants section of the control tower.
     *
     * <h2>What it matches</h2>
     *
     * <p>Merchant display and legal name, the merchant's own contact name,
     * email and phone, the owner's name, email and phone, the merchant
     * reference (M-123) and a bare id - and, through an EXISTS, any of that
     * merchant's shop names, shop codes, shop references and business names.
     * An operator who only remembers the shop finds the merchant behind it,
     * which is usually how this search is actually used.
     *
     * <h2>Why it is one statement</h2>
     *
     * <p>The shop match is an EXISTS rather than a join, so a merchant with
     * forty shops is one row and not forty. The shop COUNT is a correlated
     * subquery evaluated for the page's rows only. Two statements serve a
     * search: this one and its count. Nothing scales with the number of
     * results.
     *
     * <h2>Why the parameters are bound</h2>
     *
     * <p>Every value travels as a named parameter. The term is never
     * concatenated into SQL, so there is nothing for an operator's apostrophe
     * - or an attacker's - to terminate.
     */
    @Transactional
    public PageEnvelope<MerchantHit> searchMerchants(String rawQuery, int requestedPage,
                                                     int requestedSize) {
        String term = requireSearchTerm(rawQuery);
        int page = Math.max(0, requestedPage);
        int size = pageSize(requestedSize);

        MapSqlParameterSource params = searchParams(term, page, size);

        String where = """
                m.deleted_at IS NULL AND (
                     lower(m.display_name) LIKE :pattern
                  OR lower(m.legal_name) LIKE :pattern
                  OR lower(m.contact_name) LIKE :pattern
                  OR lower(m.contact_email) LIKE :pattern
                  OR lower(owner.full_name) LIKE :pattern
                  OR lower(owner.email) LIKE :pattern
                  OR lower('m-' || CAST(m.id AS varchar)) LIKE :pattern
                  OR CAST(m.id AS varchar) = :exact
                  OR (CAST(:digits AS varchar) IS NOT NULL AND (
                         lower(m.contact_phone) LIKE CAST(:digitsPattern AS varchar)
                      OR lower(owner.mobile_number) LIKE CAST(:digitsPattern AS varchar)))
                  OR EXISTS (
                        SELECT 1 FROM shops s
                         WHERE s.merchant_id = m.id AND s.deleted_at IS NULL
                           AND (lower(s.display_name) LIKE :pattern
                             OR lower(s.code) LIKE :pattern
                             OR lower(s.business_name) LIKE :pattern
                             OR lower('s-' || CAST(s.id AS varchar)) LIKE :pattern
                             OR CAST(s.id AS varchar) = :exact)))
                """;

        List<MerchantHit> content = jdbc.query("""
                SELECT m.id, m.display_name, m.legal_name, m.contact_email,
                       m.contact_phone, m.status, m.active, m.created_at,
                       owner.full_name AS owner_name,
                       (SELECT count(*) FROM shops s2
                         WHERE s2.merchant_id = m.id AND s2.deleted_at IS NULL) AS shop_count
                  FROM merchants m
                  LEFT JOIN customers owner ON owner.id = m.owner_customer_id
                 WHERE %s
                 ORDER BY lower(COALESCE(m.display_name, m.legal_name, '')), m.id
                 LIMIT :limit OFFSET :offset
                """.formatted(where), params, (rs, row) -> new MerchantHit(
                        rs.getLong("id"),
                        "M-" + rs.getLong("id"),
                        rs.getString("display_name"),
                        rs.getString("legal_name"),
                        rs.getString("owner_name"),
                        rs.getString("contact_email"),
                        rs.getString("contact_phone"),
                        rs.getString("status"),
                        rs.getBoolean("active"),
                        rs.getLong("shop_count"),
                        timestamp(rs, "created_at")));

        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM merchants m
                  LEFT JOIN customers owner ON owner.id = m.owner_customer_id
                 WHERE %s
                """.formatted(where), params, Long.class);

        auditSearch("PLATFORM_MERCHANT_SEARCH", term, page, content.size());
        return page(content, page, size, count == null ? 0 : count);
    }

    /**
     * The customers section of the control tower.
     *
     * <p>Matches name, email, the customer reference (C-123), a bare id, a
     * phone in any of the shapes people write them in, and an order number -
     * because a support call usually starts with "my order GPS-1234" rather
     * than with a name.
     *
     * <p>Same shape as the merchant search: one statement for the page, one
     * for the count, order and last-order date correlated for the page's rows
     * only.
     */
    @Transactional
    public PageEnvelope<CustomerHit> searchCustomers(String rawQuery, int requestedPage,
                                                     int requestedSize) {
        String term = requireSearchTerm(rawQuery);
        int page = Math.max(0, requestedPage);
        int size = pageSize(requestedSize);

        MapSqlParameterSource params = searchParams(term, page, size);

        String where = """
                     lower(c.full_name) LIKE :pattern
                  OR lower(c.email) LIKE :pattern
                  OR lower('c-' || CAST(c.id AS varchar)) LIKE :pattern
                  OR CAST(c.id AS varchar) = :exact
                  OR (CAST(:digits AS varchar) IS NOT NULL AND lower(c.mobile_number) LIKE CAST(:digitsPattern AS varchar))
                  OR EXISTS (
                        SELECT 1 FROM orders o2
                         WHERE o2.customer_id = c.id
                           AND lower(o2.order_number) LIKE :pattern)
                """;

        List<CustomerHit> content = jdbc.query("""
                SELECT c.id, c.full_name, c.email, c.mobile_number, c.active, c.created_at,
                       (SELECT count(*) FROM orders o WHERE o.customer_id = c.id) AS order_count,
                       (SELECT max(o.order_date) FROM orders o
                         WHERE o.customer_id = c.id) AS last_order_at
                  FROM customers c
                 WHERE %s
                 ORDER BY lower(c.full_name), c.id
                 LIMIT :limit OFFSET :offset
                """.formatted(where), params, (rs, row) -> new CustomerHit(
                        rs.getLong("id"),
                        "C-" + rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("mobile_number"),
                        rs.getBoolean("active"),
                        rs.getLong("order_count"),
                        timestamp(rs, "created_at"),
                        timestamp(rs, "last_order_at")));

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM customers c WHERE " + where, params, Long.class);

        auditSearch("PLATFORM_CUSTOMER_SEARCH", term, page, content.size());
        return page(content, page, size, count == null ? 0 : count);
    }

    // ----------------------------------------------------- customer profile

    /**
     * Everything a Super Admin legitimately needs about one customer.
     *
     * <p>Composes the existing {@link Customer360} the same way the merchant
     * profile composes its core, and adds the sections §11-§18 asked for:
     * addresses, where they actually buy, payments, refunds, reviews, returns
     * and real session-backed activity.
     *
     * <h2>Query budget</h2>
     *
     * <p>The core profile, then one statement each for addresses, shop
     * affinity, category affinity, payments, refunds, reviews, returns and
     * activity. Flat in the number of orders: the affinity breakdowns are
     * GROUP BY in the database, never a fetch-all-orders-and-count-in-Java.
     * §20 named that anti-pattern explicitly and a query-count test holds it.
     */
    @Transactional
    public CustomerProfile customerProfile(Long id) {
        if (id == null || id <= 0) {
            throw new BadRequestException("customerId must be positive");
        }
        Customer360 core = customer(id);
        Map<String, Object> params = Map.of("id", id);

        List<PlatformProfiles.AddressLine> addresses = jdbc.query("""
                SELECT a.id, a.label, a.house_no, a.street, a.building_name,
                       a.area, a.city, a.pincode, a.default_address
                  FROM addresses a WHERE a.customer_id = :id
                 ORDER BY a.default_address DESC, a.id
                 LIMIT 25
                """, params, (rs, row) -> new PlatformProfiles.AddressLine(
                        rs.getLong("id"), rs.getString("label"),
                        joinParts(rs.getString("house_no"), rs.getString("building_name"),
                                rs.getString("street")),
                        rs.getString("area"), rs.getString("city"), rs.getString("pincode"),
                        bool(rs.getObject("default_address"))));

        // WHERE THEY BUY, and whether they ever said so. The preferred flag is
        // an EXISTS against the customer's own saved preferences - never
        // inferred from the order count beside it. See ShopAffinity.
        List<PlatformProfiles.ShopAffinity> shops = jdbc.query("""
                SELECT o.shop_id, s.display_name, count(*) orders,
                       COALESCE(sum(o.total_amount - COALESCE(o.delivery_fee,0)),0) spent,
                       max(o.order_date) last_order,
                       EXISTS (SELECT 1 FROM customer_preferred_shops ps
                                WHERE ps.customer_id = :id
                                  AND ps.preferred_shop_id = o.shop_id) preferred
                  FROM orders o LEFT JOIN shops s ON s.id = o.shop_id
                 WHERE o.customer_id = :id
                 GROUP BY o.shop_id, s.display_name
                 ORDER BY count(*) DESC, o.shop_id
                 LIMIT 20
                """, params, (rs, row) -> new PlatformProfiles.ShopAffinity(
                        rs.getLong("shop_id"), rs.getString("display_name"),
                        rs.getLong("orders"), decimal(rs.getObject("spent")),
                        timestamp(rs, "last_order"), rs.getBoolean("preferred")));

        List<PlatformProfiles.CategoryAffinity> categories = jdbc.query("""
                SELECT c.id, c.name, count(DISTINCT o.id) orders
                  FROM orders o
                  JOIN order_items oi ON oi.order_id = o.id
                  JOIN product_variants v ON v.id = oi.product_variant_id
                  JOIN products p ON p.id = v.product_id
                  JOIN categories c ON c.id = p.category_id
                 WHERE o.customer_id = :id
                 GROUP BY c.id, c.name
                 ORDER BY count(DISTINCT o.id) DESC, c.id
                 LIMIT 10
                """, params, (rs, row) -> new PlatformProfiles.CategoryAffinity(
                        rs.getLong("id"), rs.getString("name"), rs.getLong("orders")));

        // PROVIDER REFERENCES, NEVER CREDENTIALS. provider_payment_id and
        // provider_order_id are the handles an operator quotes to a payment
        // provider when chasing a stuck payment. No card number, no CVV, no
        // UPI PIN, no provider secret and no token exists in this projection.
        List<PlatformProfiles.PaymentLine> payments = jdbc.query("""
                SELECT p.id, p.order_id, o.order_number, p.payment_method, p.payment_status,
                       p.amount, p.provider_payment_id, p.provider_order_id, p.payment_date
                  FROM payments p LEFT JOIN orders o ON o.id = p.order_id
                 WHERE o.customer_id = :id
                 ORDER BY p.payment_date DESC NULLS LAST, p.id DESC
                 LIMIT 25
                """, params, (rs, row) -> new PlatformProfiles.PaymentLine(
                        rs.getLong("id"), (Long) rs.getObject("order_id"),
                        rs.getString("order_number"), rs.getString("payment_method"),
                        rs.getString("payment_status"), decimal(rs.getObject("amount")),
                        rs.getString("provider_payment_id"), rs.getString("provider_order_id"),
                        timestamp(rs, "payment_date")));

        List<PlatformProfiles.RefundLine> refunds = jdbc.query("""
                SELECT r.id, o.id order_id, o.order_number, r.status, r.amount,
                       r.reason, r.settled_at
                  FROM refunds r
                  JOIN payments p ON p.id = r.payment_id
                  JOIN orders o ON o.id = p.order_id
                 WHERE o.customer_id = :id
                 ORDER BY r.settled_at DESC NULLS LAST, r.id DESC
                 LIMIT 25
                """, params, (rs, row) -> new PlatformProfiles.RefundLine(
                        rs.getLong("id"), (Long) rs.getObject("order_id"),
                        rs.getString("order_number"), rs.getString("status"),
                        decimal(rs.getObject("amount")), rs.getString("reason"),
                        timestamp(rs, "settled_at")));

        List<PlatformProfiles.ReviewLine> reviews = jdbc.query("""
                SELECT r.id, 'PRODUCT' kind, r.product_id target_id, p.name target_name,
                       r.rating, r.comment, r.reported_at, r.review_date created_at
                  FROM reviews r LEFT JOIN products p ON p.id = r.product_id
                 WHERE r.customer_id = :id
                UNION ALL
                SELECT sr.id, 'SHOP', sr.shop_id, s.display_name,
                       sr.rating, sr.comment, sr.reported_at, sr.created_at
                  FROM shop_ratings sr LEFT JOIN shops s ON s.id = sr.shop_id
                 WHERE sr.customer_id = :id
                 ORDER BY created_at DESC NULLS LAST
                 LIMIT 25
                """, params, (rs, row) -> new PlatformProfiles.ReviewLine(
                        rs.getLong("id"), rs.getString("kind"), (Long) rs.getObject("target_id"),
                        rs.getString("target_name"), (Integer) rs.getObject("rating"),
                        rs.getString("comment"), rs.getObject("reported_at") != null,
                        timestamp(rs, "created_at")));

        List<PlatformProfiles.ReturnLine> returns = jdbc.query("""
                SELECT ret.id, ret.order_id, o.order_number, ret.status, ret.reason,
                       ret.refund_amount, ret.requested_at, ret.decided_at
                  FROM order_returns ret LEFT JOIN orders o ON o.id = ret.order_id
                 WHERE ret.customer_id = :id
                 ORDER BY ret.requested_at DESC NULLS LAST, ret.id DESC
                 LIMIT 25
                """, params, (rs, row) -> new PlatformProfiles.ReturnLine(
                        rs.getLong("id"), (Long) rs.getObject("order_id"),
                        rs.getString("order_number"), rs.getString("status"),
                        rs.getString("reason"), decimal(rs.getObject("refund_amount")),
                        timestamp(rs, "requested_at"), timestamp(rs, "decided_at")));

        // REAL, unlike the merchant equivalent: the customer app does report
        // sessions. Still client-reported and server-capped - see the note.
        Map<String, Object> activity = jdbc.queryForMap("""
                SELECT (SELECT min(started_at) FROM customer_app_sessions WHERE customer_id=:id) first_session,
                       (SELECT max(started_at) FROM customer_app_sessions WHERE customer_id=:id) last_session,
                       (SELECT count(*) FROM customer_app_sessions WHERE customer_id=:id) sessions,
                       (SELECT COALESCE(sum(seconds),0) FROM customer_app_sessions WHERE customer_id=:id) total_seconds,
                       (SELECT count(DISTINCT CAST(started_at AS date)) FROM customer_app_sessions
                         WHERE customer_id=:id) active_days,
                       (SELECT max(order_date) FROM orders WHERE customer_id=:id) last_order
                """, params);

        long sessions = number(activity.get("sessions"));
        PlatformProfiles.CustomerActivity activeness = new PlatformProfiles.CustomerActivity(
                time(activity.get("first_session")), time(activity.get("last_session")),
                sessions, number(activity.get("total_seconds")),
                number(activity.get("active_days")), time(activity.get("last_order")),
                sessions > 0, PlatformProfiles.CustomerActivity.CLIENT_REPORTED);

        List<AuditEntry> security = auditFor(
                "a.entity_type = 'Customer' AND a.entity_id = :id", params);

        return new CustomerProfile(core, addresses, shops, categories, payments,
                refunds, reviews, returns, activeness, security);
    }

    /** Address parts that exist, comma-joined; null when the customer gave none. */
    private static String joinParts(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(part.trim());
        }
        return out.length() == 0 ? null : out.toString();
    }

    // ----------------------------------------------------- merchant profile

    /**
     * Everything a Super Admin legitimately needs about one merchant.
     *
     * <h2>Why this composes rather than replaces</h2>
     *
     * <p>{@link #merchant(Long)} already returns identity, per-shop lines and
     * merchant-wide totals, and the Flutter screens that call it still work.
     * This adds the sections that were missing - commerce across all three
     * modes, the workforce, reputation, activity and the administrative
     * record - and carries the existing {@link Merchant360} inside rather than
     * restating its fields. One definition of "this merchant's GMV", not two
     * that drift.
     *
     * <h2>Query budget</h2>
     *
     * <p>Bounded and flat: the core profile, then one grouped statement each
     * for commerce, workforce, reputation, activity and the audit page. Seven
     * statements for a merchant with one shop and seven for a merchant with
     * forty - nothing here loops over shops, orders or workers. A query-count
     * test holds that.
     */
    @Transactional
    public MerchantProfile merchantProfile(Long id, LocalDateTime from, LocalDateTime to) {
        if (id == null || id <= 0) {
            throw new BadRequestException("merchantId must be positive");
        }
        validateRange(from, to);
        Merchant360 core = merchant(id);

        Map<String, Object> params = Map.of("id", id, "from", from, "to", to);

        Map<String, Long> orderStatuses = new LinkedHashMap<>();
        jdbc.query("""
                SELECT o.order_status, count(*) total
                  FROM orders o JOIN shops s ON s.id = o.shop_id
                 WHERE s.merchant_id = :id AND o.order_date >= :from AND o.order_date < :to
                 GROUP BY o.order_status
                """, params, (RowCallbackHandler) rs ->
                orderStatuses.put(rs.getString("order_status"), rs.getLong("total")));

        Map<String, Object> listings = jdbc.queryForMap("""
                SELECT COALESCE(sum(CASE WHEN COALESCE(spv.active,true) THEN 1 ELSE 0 END),0) active_listings,
                       COALESCE(sum(CASE WHEN COALESCE(spv.commerce_mode,'ONLINE_PURCHASE')='ONLINE_PURCHASE'
                                         THEN 1 ELSE 0 END),0) online_listings,
                       COALESCE(sum(CASE WHEN spv.commerce_mode='VISIT_TO_BUY' THEN 1 ELSE 0 END),0) visit_listings,
                       COALESCE(sum(CASE WHEN spv.commerce_mode='SERVICE_AT_SHOP' THEN 1 ELSE 0 END),0) service_listings,
                       COALESCE(sum(CASE WHEN COALESCE(inv.stock,0) <= 0 THEN 1 ELSE 0 END),0) out_of_stock
                  FROM shop_product_variants spv
                  JOIN shops s ON s.id = spv.shop_id
                  LEFT JOIN inventory inv ON inv.shop_id = spv.shop_id
                                         AND inv.product_variant_id = spv.product_variant_id
                 WHERE s.merchant_id = :id AND s.deleted_at IS NULL
                """, Map.of("id", id));

        Long offers = jdbc.queryForObject("""
                SELECT count(*) FROM coupons cp JOIN shops s ON s.id = cp.shop_id
                 WHERE s.merchant_id = :id AND s.deleted_at IS NULL AND cp.active = true
                """, Map.of("id", id), Long.class);

        MerchantCommerce commerce = new MerchantCommerce(
                Map.copyOf(orderStatuses),
                number(listings.get("active_listings")),
                number(listings.get("out_of_stock")),
                number(listings.get("online_listings")),
                number(listings.get("visit_listings")),
                number(listings.get("service_listings")),
                offers == null ? 0 : offers);

        List<WorkerLine> workers = jdbc.query("""
                SELECT w.id, w.name, w.shop_id, s.display_name shop_name,
                       w.active, w.available, w.suspended_until
                  FROM delivery_partners w
                  JOIN shops s ON s.id = w.shop_id
                 WHERE s.merchant_id = :id AND w.deleted_at IS NULL AND s.deleted_at IS NULL
                 ORDER BY s.id, w.id
                 LIMIT 200
                """, Map.of("id", id), (rs, row) -> new WorkerLine(
                        rs.getLong("id"), "W-" + rs.getLong("id"), rs.getString("name"),
                        rs.getLong("shop_id"), rs.getString("shop_name"),
                        bool(rs.getObject("active")), bool(rs.getObject("available")),
                        timestamp(rs, "suspended_until")));
        long activeWorkers = workers.stream().filter(WorkerLine::active).count();
        MerchantWorkforce workforce = new MerchantWorkforce(
                workers.size(), activeWorkers, workers.size() - activeWorkers, workers);

        Map<String, Object> reputation = jdbc.queryForMap("""
                SELECT COALESCE(avg(sr.rating),0) average_rating,
                       COALESCE(avg(sr.rating) FILTER (WHERE sr.created_at >= :recent),0) recent_rating,
                       count(sr.id) rating_count,
                       COALESCE(sum(CASE WHEN sr.reported_at IS NOT NULL THEN 1 ELSE 0 END),0) reported
                  FROM shop_ratings sr JOIN shops s ON s.id = sr.shop_id
                 WHERE s.merchant_id = :id AND sr.hidden_at IS NULL
                """, Map.of("id", id, "recent", to.minusDays(90)));

        Map<String, Object> grief = jdbc.queryForMap("""
                SELECT COALESCE(count(ret.id),0) returns_requested,
                       COALESCE(sum(CASE WHEN ret.status='APPROVED' THEN 1 ELSE 0 END),0) returns_approved
                  FROM order_returns ret JOIN shops s ON s.id = ret.shop_id
                 WHERE s.merchant_id = :id
                """, Map.of("id", id));

        Map<String, Object> refunds = jdbc.queryForMap("""
                SELECT count(r.id) refund_count, COALESCE(sum(r.amount),0) refund_amount
                  FROM refunds r
                  JOIN payments p ON p.id = r.payment_id
                  JOIN orders o ON o.id = p.order_id
                  JOIN shops s ON s.id = o.shop_id
                 WHERE s.merchant_id = :id AND r.status='SUCCEEDED'
                """, Map.of("id", id));

        MerchantReputation reviews = new MerchantReputation(
                decimal(reputation.get("average_rating")),
                decimal(reputation.get("recent_rating")),
                number(reputation.get("rating_count")),
                number(reputation.get("reported")),
                number(grief.get("returns_requested")),
                number(grief.get("returns_approved")),
                number(refunds.get("refund_count")),
                decimal(refunds.get("refund_amount")));

        Map<String, Object> activity = jdbc.queryForMap("""
                SELECT (SELECT min(o.order_date) FROM orders o JOIN shops s ON s.id=o.shop_id
                         WHERE s.merchant_id=:id) first_order,
                       (SELECT max(o.order_date) FROM orders o JOIN shops s ON s.id=o.shop_id
                         WHERE s.merchant_id=:id) last_order,
                       (SELECT max(spv.updated_at) FROM shop_product_variants spv
                          JOIN shops s ON s.id=spv.shop_id WHERE s.merchant_id=:id) last_listing,
                       (SELECT max(a.occurred_at) FROM audit_logs a WHERE a.merchant_id=:id) last_admin,
                       (SELECT count(DISTINCT CAST(o.order_date AS date)) FROM orders o
                          JOIN shops s ON s.id=o.shop_id
                         WHERE s.merchant_id=:id AND o.order_date>=:from AND o.order_date<:to) active_days
                """, params);

        MerchantActivity activeness = new MerchantActivity(
                time(activity.get("first_order")), time(activity.get("last_order")),
                time(activity.get("last_listing")), time(activity.get("last_admin")),
                number(activity.get("active_days")),
                false, MerchantActivity.NOT_MEASURED);

        List<AuditEntry> security = auditFor("a.merchant_id = :id", Map.of("id", id));

        return new MerchantProfile(core, commerce, workforce, reviews, activeness,
                security, from, to);
    }

    /**
     * The administrative record, newest first.
     *
     * <p>Bounded at fifty on purpose: a 360 screen is a summary and the audit
     * screen already exists for the full history. An unbounded read here is
     * how one merchant with years of events makes this endpoint slow for
     * everybody.
     */
    private List<AuditEntry> auditFor(String predicate, Map<String, ?> params) {
        return jdbc.query("""
                SELECT a.id, a.action, a.actor_email, a.actor_role, a.entity_type,
                       a.entity_id, a.previous_state, a.new_state, a.reason, a.occurred_at
                  FROM audit_logs a
                 WHERE %s
                 ORDER BY a.occurred_at DESC, a.id DESC
                 LIMIT 50
                """.formatted(predicate), params, (rs, row) -> new AuditEntry(
                        rs.getLong("id"), rs.getString("action"), rs.getString("actor_email"),
                        rs.getString("actor_role"), rs.getString("entity_type"),
                        (Long) rs.getObject("entity_id"), rs.getString("previous_state"),
                        rs.getString("new_state"), rs.getString("reason"),
                        timestamp(rs, "occurred_at")));
    }

    // ------------------------------------------------------- search plumbing

    private static String requireSearchTerm(String rawQuery) {
        String term = rawQuery == null ? "" : rawQuery.trim();
        if (term.length() < 2) {
            throw new BadRequestException("Search requires at least 2 characters");
        }
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new BadRequestException("Search is limited to 120 characters");
        }
        return term;
    }

    /**
     * The bound values every directory search uses.
     *
     * <p>{@code exact} lets a bare id find exactly one row rather than every
     * row whose id merely contains those characters - typing 7 should not
     * return customer 7, 17, 70 and 1007 above the person you wanted.
     *
     * <p>{@code digits} is null when the term is not numeric enough to be a
     * phone number, and the SQL tests for that before applying the phone
     * predicate. Without it, searching a name would compare every phone
     * column against a nonsense pattern for nothing.
     */
    private static MapSqlParameterSource searchParams(String term, int page, int size) {
        String digits = com.gpstore.auth.IndianPhoneNumbers.searchDigits(term);
        return new MapSqlParameterSource()
                .addValue("pattern", "%" + term.toLowerCase(Locale.ROOT) + "%")
                .addValue("exact", term)
                .addValue("digits", digits)
                .addValue("digitsPattern", digits == null ? null : "%" + digits + "%")
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact((long) page, size));
    }

    /**
     * THE TERM ITSELF IS NEVER WRITTEN DOWN. It is routinely somebody's phone
     * number or email address, and an audit log that records who searched for
     * whom is a second copy of the personal data the log exists to protect.
     * What is recorded is that a search happened, how long the term was, and
     * how much came back.
     */
    private void auditSearch(String action, String term, int page, int resultCount) {
        audit.logRequired(action, "PlatformSearch", null, null, null, null, null,
                "platform investigation",
                "queryLength=" + term.length() + ", page=" + page
                        + ", resultCount=" + resultCount);
    }

    private static LocalDateTime timestamp(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

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
                        rs.getString("subtitle"), rs.getString("email"),
                        rs.getString("phone")));
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
                WHERE lower(c.full_name) LIKE :pattern
                   OR lower(c.email) LIKE :pattern
                   OR lower(c.mobile_number) LIKE :pattern
                   OR lower('C-' || CAST(c.id AS varchar)) LIKE :pattern
                UNION ALL
                SELECT 'MERCHANT', m.id, COALESCE(m.display_name, m.legal_name),
                       'M-' || CAST(m.id AS varchar), m.status,
                       m.contact_email, m.contact_phone
                FROM merchants m
                LEFT JOIN customers owner ON owner.id=m.owner_customer_id
                WHERE m.deleted_at IS NULL AND (
                      lower(m.legal_name) LIKE :pattern
                   OR lower(m.display_name) LIKE :pattern
                   OR lower(m.contact_email) LIKE :pattern
                   OR lower(m.contact_phone) LIKE :pattern
                   OR lower(owner.full_name) LIKE :pattern
                   OR lower(owner.email) LIKE :pattern
                   OR lower(owner.mobile_number) LIKE :pattern
                   OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern)
                UNION ALL
                SELECT 'SHOP', s.id, s.display_name,
                       COALESCE(s.code, 'S-' || CAST(s.id AS varchar)),
                       'Merchant M-' || CAST(s.merchant_id AS varchar), NULL, s.support_phone
                FROM shops s JOIN merchants m ON m.id=s.merchant_id
                WHERE s.deleted_at IS NULL AND (
                      lower(s.display_name) LIKE :pattern
                   OR lower(s.code) LIKE :pattern
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
                WHERE lower(o.order_number) LIKE :pattern
                   OR lower(c.full_name) LIKE :pattern
                   OR lower(pay.transaction_id) LIKE :pattern
                   OR lower(pay.provider_order_id) LIKE :pattern
                   OR lower(pay.provider_payment_id) LIKE :pattern
                   OR lower(s.display_name) LIKE :pattern
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
                      lower(w.name) LIKE :pattern
                   OR lower(w.mobile) LIKE :pattern
                   OR lower(w.login_email) LIKE :pattern
                   OR lower(s.display_name) LIKE :pattern
                   OR lower(COALESCE(m.display_name, m.legal_name, '')) LIKE :pattern
                   OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern
                   OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern
                   OR lower('W-' || CAST(w.id AS varchar)) LIKE :pattern)
                UNION ALL
                SELECT 'PRODUCT', p.id, p.name,
                       'P-' || CAST(p.id AS varchar),
                       COALESCE(p.brand, 'Catalogue product'), NULL, NULL
                FROM products p
                WHERE lower(p.name) LIKE :pattern
                   OR lower(p.brand) LIKE :pattern
                   OR EXISTS (SELECT 1 FROM product_variants pv WHERE pv.product_id=p.id
                              AND (lower(pv.sku) LIKE :pattern
                                   OR lower(pv.barcode) LIKE :pattern))
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
        return new DashboardSummary(from, to, counts, commerceModeMix(range),
                Map.copyOf(statuses), finance,
                snapshot.onlineNow(), snapshot.windowSeconds(), snapshot.available());
    }

    /**
     * What the marketplace is actually made of, beyond what produced orders.
     *
     * <p>ONE STATEMENT FOR THE LISTINGS and one for the interest, both grouped
     * in the database. This runs on the Super Admin dashboard, which loads
     * across every shop on the platform - a per-mode or per-shop loop here is
     * the shape that cost this application its ceiling once already.
     *
     * <p>Cross-shop on purpose and by permission: reaching every shop IS the
     * control tower's job, and PLATFORM_ADMIN in SecurityConfig is what gates
     * it rather than a tenant scope.
     */
    private CommerceModeMix commerceModeMix(MapSqlParameterSource range) {
        Map<String, Long> listings = new LinkedHashMap<>();
        Map<String, Long> shopsWith = new LinkedHashMap<>();
        jdbc.query("""
                SELECT COALESCE(spv.commerce_mode, 'ONLINE_PURCHASE') AS mode,
                       count(*)                       AS listings,
                       count(DISTINCT spv.shop_id)    AS shops
                  FROM shop_product_variants spv
                  JOIN shops s ON s.id = spv.shop_id AND s.deleted_at IS NULL
                 WHERE COALESCE(spv.active, true) = true
                 GROUP BY COALESCE(spv.commerce_mode, 'ONLINE_PURCHASE')
                """, Map.of(), (RowCallbackHandler) rs -> {
            listings.put(rs.getString("mode"), rs.getLong("listings"));
            shopsWith.put(rs.getString("mode"), rs.getLong("shops"));
        });

        Map<String, Long> engagement = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.commerce_mode AS mode, count(*) AS total
                  FROM listing_engagement_events e
                 WHERE e.occurred_at >= :from AND e.occurred_at < :to
                 GROUP BY e.commerce_mode
                """, range, (RowCallbackHandler) rs ->
                engagement.put(rs.getString("mode"), rs.getLong("total")));

        return new CommerceModeMix(
                listings.getOrDefault("ONLINE_PURCHASE", 0L),
                listings.getOrDefault("VISIT_TO_BUY", 0L),
                listings.getOrDefault("SERVICE_AT_SHOP", 0L),
                shopsWith.getOrDefault("ONLINE_PURCHASE", 0L),
                shopsWith.getOrDefault("VISIT_TO_BUY", 0L),
                shopsWith.getOrDefault("SERVICE_AT_SHOP", 0L),
                Map.copyOf(engagement),
                com.gpstore.engagement.ListingEngagement.EngagementReport.NOTE);
    }

    @Transactional(readOnly = true)
    public Customer360 customer(Long id) {
        List<CustomerIdentity> identities = jdbc.query("""
                SELECT id, full_name, email, mobile_number, role, enabled, active, verified,
                       created_at, profile_image_url
                FROM customers WHERE id = :id
                """, Map.of("id", id), (rs, row) -> new CustomerIdentity(
                rs.getLong("id"), "C-" + rs.getLong("id"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("mobile_number"),
                rs.getString("role"), bool(rs.getObject("enabled")), bool(rs.getObject("active")),
                bool(rs.getObject("verified")), time(rs.getObject("created_at")),
                rs.getString("profile_image_url")));
        if (identities.isEmpty()) throw new ResourceNotFoundException("Customer not found");

        Map<String, Long> status = statusCounts("SELECT order_status, count(*) total FROM orders WHERE customer_id = :id GROUP BY order_status", id);
        long total = status.values().stream().mapToLong(Long::longValue).sum();
        long completed = status.getOrDefault("COMPLETED", 0L) + status.getOrDefault("DELIVERED", 0L);
        long cancelled = status.getOrDefault("CANCELLED", 0L) + status.getOrDefault("REJECTED", 0L);
        long failed = status.getOrDefault("DELIVERY_FAILED", 0L);
        long active = Math.max(0, total - completed - cancelled - failed);
        long returned = count("SELECT count(*) FROM order_returns r JOIN orders o ON o.id = r.order_id WHERE o.customer_id = :id", Map.of("id", id));
        long refundedOrders = count("SELECT count(DISTINCT o.id) FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=p.order_id WHERE o.customer_id=:id AND r.status='SUCCEEDED'", Map.of("id", id));
        // ONE STATEMENT, FOUR FACTS. The average and the last-order date come
        // out of the scan that was already counting purchases, rather than
        // two more round trips for numbers the same rows already hold.
        Map<String, Object> finance = jdbc.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN order_status IN ('DELIVERED','COMPLETED') THEN total_amount ELSE 0 END),0) purchases,
                       COALESCE(SUM(cancellation_fee),0) cancellation,
                       COALESCE(AVG(CASE WHEN order_status IN ('DELIVERED','COMPLETED') THEN total_amount END),0) average_completed,
                       MAX(order_date) last_order_at
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
                        decimal(finance.get("cancellation")),
                        decimal(finance.get("average_completed")),
                        time(finance.get("last_order_at"))),
                reviews, reported, recent);
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

    /**
     * Bar a customer from the marketplace, or let them back in.
     *
     * <h2>This calls the existing route, it does not become a second one</h2>
     *
     * <p>{@code CustomerService.setAccountActive} already flips the flag,
     * revokes every refresh token the account holds and drops the two-second
     * JWT status cache so a live session dies on the next tap. Writing a
     * second implementation here would mean two places that can bar somebody
     * and only one of them logging them out. So this validates what Super
     * Admin additionally owes - a reason - and delegates.
     *
     * <p>THE REASON IS MANDATORY ON THIS PATH. Five characters is not a high
     * bar, but it is the difference between an audit row that says an
     * operator acted and one that says why.
     */
    public Map<String, Object> setCustomerActive(Long id, boolean active, String reason) {
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            throw new BadRequestException("A reason of 5 to 500 characters is required");
        }
        var saved = customers.setAccountActive(id, active, reason.trim());
        return Map.of("customerId", id,
                "active", Boolean.TRUE.equals(saved.getActive()));
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
                rs.getString("contact_email"), rs.getString("contact_phone"),
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
        // Operational contact data is intentionally complete for the
        // PLATFORM_ADMIN-only control tower. Canonical keys are additive;
        // the legacy "maskedEmail"/"maskedPhone" keys stay in the wire shape
        // so deployed app builds keep parsing the response, but their values
        // are no longer masked. No query here selects credentials or secrets.
        if (resource.equals("workers")) {
            content.forEach(row -> row.put("maskedPhone", row.get("phone")));
        } else if (resource.equals("customers") || resource.equals("merchants")) {
            content.forEach(row -> {
                row.put("maskedPhone", row.get("phone"));
                row.put("maskedEmail", row.get("email"));
            });
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
                    SELECT c.id id,c.full_name name,c.email email,c.mobile_number phone,
                           c.enabled enabled,c.active active,c.verified verified,c.created_at "createdAt",
                           (SELECT count(*) FROM orders o WHERE o.customer_id=c.id) orders
                    """, "FROM customers c",
                    "lower(c.full_name) LIKE :pattern OR lower(c.email) LIKE :pattern OR lower(c.mobile_number) LIKE :pattern OR lower('C-' || CAST(c.id AS varchar)) LIKE :pattern",
                    "ORDER BY c.id DESC");
            case "merchants" -> new ResourceSql("""
                    SELECT m.id id,COALESCE(m.display_name,m.legal_name) name,m.legal_name "legalName",
                           m.contact_email email,m.contact_phone phone,m.status status,
                           m.status_reason "statusReason",m.active active,m.created_at "createdAt",
                           (SELECT count(*) FROM shops s WHERE s.merchant_id=m.id AND s.deleted_at IS NULL) shops
                    """, "FROM merchants m",
                    "m.deleted_at IS NULL AND (lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower(m.contact_email) LIKE :pattern OR lower(m.contact_phone) LIKE :pattern OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern)",
                    "ORDER BY m.id DESC");
            case "shops" -> new ResourceSql("""
                    SELECT s.id id,s.display_name name,s.code code,s.status status,s.status_reason "statusReason",
                           s.active active,s.verification_level verification,s.city city,s.state state,
                           m.id "merchantId",COALESCE(m.display_name,m.legal_name) merchant
                    """, "FROM shops s JOIN merchants m ON m.id=s.merchant_id",
                    "s.deleted_at IS NULL AND (lower(s.display_name) LIKE :pattern OR lower(s.code) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern)",
                    "ORDER BY s.id DESC");
            case "orders" -> new ResourceSql("""
                    SELECT o.id id,o.order_number "orderNumber",o.order_date "orderedAt",
                           o.order_status status,o.payment_status "paymentStatus",o.total_amount total,
                           o.delivery_fee "deliveryCharge",c.full_name customer,
                           s.id "shopId",s.display_name shop,m.id "merchantId",
                           COALESCE(m.display_name,m.legal_name) merchant
                    """, "FROM orders o LEFT JOIN customers c ON c.id=o.customer_id JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id",
                    "lower(o.order_number) LIKE :pattern OR lower('O-' || CAST(o.id AS varchar)) LIKE :pattern OR lower(c.full_name) LIKE :pattern OR lower(s.display_name) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR EXISTS (SELECT 1 FROM payments pay WHERE pay.order_id=o.id AND (lower(pay.transaction_id) LIKE :pattern OR lower(pay.provider_order_id) LIKE :pattern OR lower(pay.provider_payment_id) LIKE :pattern))",
                    "ORDER BY o.order_date DESC");
            case "workers" -> new ResourceSql("""
                    SELECT w.id id,w.name name,w.mobile phone,w.available available,w.active active,
                           w.vehicle_type "vehicleType",w.vehicle_number "vehicleNumber",
                           s.id "shopId",s.display_name shop,m.id "merchantId",
                           COALESCE(m.display_name,m.legal_name) merchant,
                           (SELECT count(*) FROM orders o WHERE o.assigned_worker_partner_id=w.id AND o.order_status IN ('READY_TO_DISPATCH','OUT_FOR_DELIVERY')) "activeOrders",
                           (SELECT count(*) FROM orders o WHERE o.assigned_worker_partner_id=w.id AND o.order_status IN ('DELIVERED','COMPLETED')) "completedDeliveries"
                    """, "FROM delivery_partners w JOIN shops s ON s.id=w.shop_id JOIN merchants m ON m.id=s.merchant_id",
                    "lower(w.name) LIKE :pattern OR lower(w.mobile) LIKE :pattern OR lower('W-' || CAST(w.id AS varchar)) LIKE :pattern OR lower(s.display_name) LIKE :pattern OR lower('S-' || CAST(s.id AS varchar)) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower('M-' || CAST(m.id AS varchar)) LIKE :pattern",
                    "ORDER BY w.id DESC");
            case "products" -> new ResourceSql("""
                    SELECT spv.id id,p.id "productId",p.name product,p.brand brand,c.name category,
                           pv.id "variantId",pv.sku sku,pv.barcode barcode,
                           spv.selling_price "sellingPrice",spv.mrp mrp,spv.available available,spv.active active,
                           COALESCE(i.stock,0) stock,COALESCE(i.reserved_stock,0) "reservedStock",
                           s.id "shopId",s.display_name shop,m.id "merchantId"
                    """, "FROM shop_product_variants spv JOIN product_variants pv ON pv.id=spv.product_variant_id JOIN products p ON p.id=pv.product_id LEFT JOIN categories c ON c.id=p.category_id JOIN shops s ON s.id=spv.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN inventory i ON i.shop_id=s.id AND i.product_variant_id=pv.id",
                    "lower(p.name) LIKE :pattern OR lower(p.brand) LIKE :pattern OR lower('P-' || CAST(p.id AS varchar)) LIKE :pattern OR lower(pv.sku) LIKE :pattern OR lower(pv.barcode) LIKE :pattern OR lower(s.display_name) LIKE :pattern",
                    "ORDER BY p.name,spv.id");
            case "payments" -> new ResourceSql("""
                    SELECT p.id id,o.id "orderId",o.order_number "orderNumber",p.amount amount,
                           p.payment_method method,p.payment_status status,p.provider provider,
                           p.transaction_id "transactionReference",p.provider_order_id "providerOrderReference",
                           p.payment_date "createdAt",s.id "shopId",s.display_name shop,
                           m.id "merchantId",c.full_name customer
                    """, "FROM payments p JOIN orders o ON o.id=p.order_id JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN customers c ON c.id=o.customer_id",
                    "lower(o.order_number) LIKE :pattern OR lower(p.transaction_id) LIKE :pattern OR lower(p.provider_order_id) LIKE :pattern OR lower(s.display_name) LIKE :pattern",
                    "ORDER BY p.payment_date DESC,p.id DESC");
            case "refunds" -> new ResourceSql("""
                    SELECT r.id id,r.refund_id "refundReference",r.amount amount,r.status status,
                           r.channel channel,r.reason reason,r.failure_reason "failureReason",
                           r.requested_at "requestedAt",r.settled_at "settledAt",
                           p.id "paymentId",o.id "orderId",o.order_number "orderNumber",
                           s.id "shopId",s.display_name shop,m.id "merchantId",c.full_name customer
                    """, "FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=p.order_id JOIN shops s ON s.id=o.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN customers c ON c.id=o.customer_id",
                    "lower(r.refund_id) LIKE :pattern OR lower(o.order_number) LIKE :pattern OR lower(s.display_name) LIKE :pattern",
                    "ORDER BY r.created_at DESC,r.id DESC");
            case "returns" -> new ResourceSql("""
                    SELECT r.id id,r.status status,r.reason reason,r.decision_note "decisionNote",
                           r.refund_amount "refundAmount",r.refund_id "refundReference",
                           r.requested_at "requestedAt",r.decided_at "decidedAt",
                           o.id "orderId",o.order_number "orderNumber",s.id "shopId",s.display_name shop,
                           c.full_name customer
                    """, "FROM order_returns r JOIN orders o ON o.id=r.order_id JOIN shops s ON s.id=o.shop_id LEFT JOIN customers c ON c.id=r.customer_id",
                    "lower(o.order_number) LIKE :pattern OR lower(s.display_name) LIKE :pattern OR lower(c.full_name) LIKE :pattern",
                    "ORDER BY r.requested_at DESC,r.id DESC");
            case "reviews" -> new ResourceSql("""
                    SELECT r.id id,'PRODUCT' "reviewType",r.rating rating,r.comment review,
                           r.review_date "createdAt",r.reported_at "reportedAt",r.hidden_at "hiddenAt",
                           p.id "productId",p.name product,c.full_name customer,r.responding_shop_id "shopId"
                    """, "FROM reviews r JOIN products p ON p.id=r.product_id LEFT JOIN customers c ON c.id=r.customer_id",
                    "lower(p.name) LIKE :pattern OR lower(c.full_name) LIKE :pattern OR lower(r.comment) LIKE :pattern",
                    "ORDER BY r.review_date DESC,r.id DESC");
            case "shop-reviews" -> new ResourceSql("""
                    SELECT r.id id,'SHOP' "reviewType",r.rating rating,r.comment review,
                           r.created_at "createdAt",r.reported_at "reportedAt",r.hidden_at "hiddenAt",
                           s.id "shopId",s.display_name shop,m.id "merchantId",
                           COALESCE(m.display_name,m.legal_name) merchant,c.full_name customer
                    """, "FROM shop_ratings r JOIN shops s ON s.id=r.shop_id JOIN merchants m ON m.id=s.merchant_id LEFT JOIN customers c ON c.id=r.customer_id",
                    "lower(s.display_name) LIKE :pattern OR lower(COALESCE(m.display_name,m.legal_name,'')) LIKE :pattern OR lower(c.full_name) LIKE :pattern OR lower(r.comment) LIKE :pattern",
                    "ORDER BY r.created_at DESC,r.id DESC");
            case "audit" -> new ResourceSql("""
                    SELECT a.id id,a.occurred_at "occurredAt",a.actor_customer_id "actorUserId",
                           a.actor_email "actorEmail",a.actor_role "actorRole",a.action action,
                           a.entity_type "targetType",a.entity_id "targetId",a.merchant_id "merchantId",
                           a.shop_id "shopId",a.previous_state "previousState",a.new_state "newState",
                           a.reason reason,a.request_id "requestId",a.details details
                    """, "FROM audit_logs a",
                    "lower(a.action) LIKE :pattern OR lower(a.entity_type) LIKE :pattern OR lower(a.actor_email) LIKE :pattern OR lower(a.request_id) LIKE :pattern",
                    "ORDER BY a.occurred_at DESC,a.id DESC");
            case "security" -> new ResourceSql("""
                    SELECT a.id id,a.occurred_at "occurredAt",a.actor_customer_id "actorUserId",
                           a.actor_email "actorEmail",a.actor_role "actorRole",a.action action,
                           a.entity_type "targetType",a.entity_id "targetId",a.merchant_id "merchantId",
                           a.shop_id "shopId",a.previous_state "previousState",a.new_state "newState",
                           a.reason reason,a.request_id "requestId",a.details details
                    """, "FROM audit_logs a",
                    "lower(a.action) LIKE :pattern OR lower(a.entity_type) LIKE :pattern OR lower(a.actor_email) LIKE :pattern OR lower(a.request_id) LIKE :pattern",
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
}
