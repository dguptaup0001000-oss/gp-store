package com.gpstore.platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The shapes a Super Admin 360 profile is made of.
 *
 * <p>SEPARATE FROM {@link PlatformControlTowerService} because that class was
 * already a thousand lines before this feature, and a record declaration is
 * not the part of it anybody needs to read while following a query. Nothing
 * here has behaviour beyond deriving a label from data already present.
 *
 * <p>NOTHING HERE HOLDS A SECRET. No password, no hash, no OTP, no activation
 * code, no token, no API or payment credential appears in any of these
 * records, and a test asserts that by reflection over every component name.
 * "Everything about this merchant" means everything operational - it does not
 * mean the things that would let somebody become them.
 */
public final class PlatformProfiles {

    private PlatformProfiles() {
    }

    // ------------------------------------------------------------ merchant

    /**
     * How busy a merchant has been, from records GP-STORE actually keeps.
     *
     * <h2>Why there is no "total active time"</h2>
     *
     * <p>The spec asked for active time and allowed two honest answers: show
     * only what can be proven, or build telemetry that can measure it. This is
     * the first, and the reason is in the customer app's own session tracker:
     * "Admin and worker apps pass null, so staff phones are not timed at all."
     * That was a deliberate privacy decision - staff are not surveilled on
     * their own phones - and {@code customer_app_sessions} therefore holds no
     * merchant rows to aggregate.
     *
     * <p>So there is no merchant active-time figure here, and inventing one
     * from order timestamps would be exactly the fabrication the spec forbade:
     * a merchant who took four orders spread across a day was not "active for
     * eight hours". {@link #sessionsMeasured} says plainly that it is not
     * measured, and {@link #note} says why, so a screen cannot present silence
     * as zero.
     *
     * <p>WHAT IS PROVEN INSTEAD: the first and last order this merchant's
     * shops took, the last time a listing was touched, the last administrative
     * event against them, and how many distinct days in the window carried an
     * order. Every one of those is a stored timestamp, not an estimate.
     */
    public record MerchantActivity(LocalDateTime firstOrderAt,
                                   LocalDateTime lastOrderAt,
                                   LocalDateTime lastListingUpdateAt,
                                   LocalDateTime lastAdminEventAt,
                                   long activeDaysInWindow,
                                   boolean sessionsMeasured,
                                   String note) {

        public static final String NOT_MEASURED =
                "GP-STORE does not time merchant or staff app usage: the admin and worker "
                        + "apps deliberately do not report sessions, so there is no stored "
                        + "active-time to total. The dates here are real events - orders, "
                        + "listing edits, administrative actions - and nothing on this card "
                        + "is estimated from them.";

        /** The most recent thing this merchant demonstrably did. */
        public LocalDateTime lastActivityAt() {
            LocalDateTime latest = null;
            for (LocalDateTime candidate :
                    new LocalDateTime[] {lastOrderAt, lastListingUpdateAt, lastAdminEventAt}) {
                if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                    latest = candidate;
                }
            }
            return latest;
        }
    }

    /**
     * What the merchant is actually trading, across all three commerce modes.
     *
     * <p>The Visit-to-Buy and Service counts matter here for the same reason
     * they matter on the Super Admin dashboard: every other number on a
     * merchant profile comes from orders, and those two modes produce none. A
     * jeweller with two hundred showroom listings and no online orders is not
     * an inactive merchant, and without these counts they read as one.
     */
    public record MerchantCommerce(Map<String, Long> orderStatuses,
                                   long activeListings,
                                   long outOfStockListings,
                                   long onlineListings,
                                   long visitToBuyListings,
                                   long serviceListings,
                                   long activeOffers) {}

    /** One worker, as an operator investigating a shop needs to see them. */
    public record WorkerLine(Long id, String workerRef, String name, Long shopId,
                             String shopName, boolean active, boolean available,
                             LocalDateTime suspendedUntil) {}

    /**
     * The merchant's people.
     *
     * <p>DELIBERATELY NARROW. A rider's name, which shop they work for and
     * whether they are currently on duty are operational facts a platform
     * operator needs to resolve a delivery problem. Their live coordinates,
     * their login email and their vehicle registration are not, so none of
     * them is here - §6 said not to expose unnecessary private worker data,
     * and a worker is the least powerful person in this system.
     */
    public record MerchantWorkforce(long total, long active, long inactive,
                                    List<WorkerLine> workers) {}

    /**
     * What customers thought, and what went wrong.
     *
     * @param recentRating the last 90 days, beside the lifetime figure,
     *                     because a shop that was good for two years and bad
     *                     for two months is a different problem from one that
     *                     was always bad, and a single average hides which.
     */
    public record MerchantReputation(BigDecimal averageRating, BigDecimal recentRating,
                                     long ratingCount, long reportedReviews,
                                     long returnsRequested, long returnsApproved,
                                     long refundCount, BigDecimal refundAmount) {}

    /** One administrative event, in the shape §9 asked for. */
    public record AuditEntry(Long id, String action, String actorEmail, String actorRole,
                             String entityType, Long entityId, String previousState,
                             String newState, String reason, LocalDateTime occurredAt) {}

    // ------------------------------------------------------------ customer

    /** A saved delivery address, as an operator resolving a delivery sees it. */
    public record AddressLine(Long id, String label, String line, String area,
                              String city, String pincode, boolean isDefault) {}

    /**
     * Where this customer actually buys, counted from real orders.
     *
     * <p>THE DISTINCTION THIS RECORD EXISTS TO PROTECT. {@link #preferred} is
     * true only when the customer explicitly saved that shop as a preference.
     * {@link #orders} being high means they buy there often, which is a
     * different fact entirely - it may mean they like the shop, or only that
     * it is the one that delivers to their street.
     *
     * <p>Calling the second one "preferred" would put words in the customer's
     * mouth, and those words would then be used to justify routing their next
     * order there. §13 said not to, and keeping them as two separate fields on
     * one row is what makes it hard to conflate them by accident.
     */
    public record ShopAffinity(Long shopId, String shopName, long orders,
                               BigDecimal spent, LocalDateTime lastOrderAt,
                               boolean preferred) {}

    /** A category this customer buys from, by order count. */
    public record CategoryAffinity(Long categoryId, String categoryName, long orders) {}

    /**
     * How this customer uses the app.
     *
     * <p>UNLIKE A MERCHANT, THIS IS REAL. The customer app does report
     * sessions ({@code customer_app_sessions}), so total time is a stored SUM
     * rather than an estimate.
     *
     * <p>It is still not evidence, and {@link #note} says so: the duration
     * comes from the customer's own phone and is capped server-side, which is
     * good enough to tell a regular from somebody who signed up and never came
     * back, and not good enough to bill anyone against.
     */
    public record CustomerActivity(LocalDateTime firstSessionAt, LocalDateTime lastSessionAt,
                                   long sessions, long totalSeconds, long activeDays,
                                   LocalDateTime lastOrderAt, boolean sessionsMeasured,
                                   String note) {

        public static final String CLIENT_REPORTED =
                "Session time is reported by the customer's own phone and capped by the "
                        + "server. It is good enough to tell a regular from somebody who "
                        + "signed up once, and it is not evidence and not billable.";
    }

    /** One payment, with references an operator can quote to a provider. */
    public record PaymentLine(Long id, Long orderId, String orderNumber, String method,
                              String status, BigDecimal amount, String providerPaymentId,
                              String providerOrderId, LocalDateTime createdAt) {}

    /** One refund against one payment. */
    public record RefundLine(Long id, Long orderId, String orderNumber, String status,
                             BigDecimal amount, String reason, LocalDateTime settledAt) {}

    /** One review this customer left. */
    public record ReviewLine(Long id, String kind, Long targetId, String targetName,
                             Integer rating, String comment, boolean reported,
                             LocalDateTime createdAt) {}

    /** One return this customer asked for. */
    public record ReturnLine(Long id, Long orderId, String orderNumber, String status,
                             String reason, BigDecimal refundAmount,
                             LocalDateTime requestedAt, LocalDateTime decidedAt) {}
}
