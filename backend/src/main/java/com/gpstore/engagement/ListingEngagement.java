package com.gpstore.engagement;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.platform.ShopScopeSwitch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a merchant finds out whether an offline listing is working.
 *
 * <h2>The gap this fills</h2>
 *
 * <p>An online sale records itself. There is an order, a payment and a
 * receipt, and the merchant's dashboard counts them. A Visit-to-Buy listing
 * has none of that: the customer sees the card, taps Directions, walks in and
 * pays in cash. GP-STORE never learns whether any of it happened, so a
 * jeweller who lists their showroom stock has no way to tell whether the
 * listing brought anybody in.
 *
 * <h2>THE LINE THIS MUST NOT CROSS</h2>
 *
 * <p>What is recorded is INTEREST: the card was seen, the detail was opened,
 * directions were asked for, the shop was rung. Every one of those is
 * something the app genuinely observed.
 *
 * <p>NOTHING HERE IS A SALE, and nothing built on it may be presented as one.
 * GP-STORE must never tell a merchant "you sold Rs 1 lakh" on the strength of
 * views and directions. It does not know how many people walked in, how many
 * bought, or what they paid - and a platform that bills against invented
 * numbers is charging for trade it did not witness. So there is no value
 * field, no conversion rate and no revenue estimate anywhere in this class,
 * and {@link EngagementReport} says in its own words what its numbers are not.
 *
 * <h2>What is not kept</h2>
 *
 * <p>A customer id when there is one, and nothing else. No device id, no IP,
 * no session token, no dwell time - nothing that could reconstruct one
 * person's walk through the app. Anonymous taps are recorded with no
 * identifier at all rather than with a pseudonymous one.
 */
@Service
public class ListingEngagement {

    private static final Logger log = LoggerFactory.getLogger(ListingEngagement.class);

    private final ListingEngagementRepository events;
    private final ShopProductVariantRepository listings;
    private final ShopScopeSwitch shopScope;

    public ListingEngagement(ListingEngagementRepository events,
                             ShopProductVariantRepository listings,
                             ShopScopeSwitch shopScope) {
        this.events = events;
        this.listings = listings;
        this.shopScope = shopScope;
    }

    /**
     * Records one thing a customer did about one shop's listing.
     *
     * <p>THE MODE IS READ FROM THE LISTING, NEVER FROM THE CALLER. A client
     * that could assert "this was a Visit-to-Buy view" could dress ordinary
     * online browsing up as showroom interest, which is exactly the number a
     * merchant would be tempted to pay for position against.
     *
     * <p>THE LISTING IS READ INSIDE THE NAMED SHOP'S SCOPE, so an event can
     * only ever be recorded against a listing that shop actually has. A
     * request naming another shop's variant writes nothing.
     *
     * <p>FAILS SOFT. Analytics must never break browsing: a customer tapping
     * Directions gets their directions whether or not this row was written.
     */
    @Transactional
    public void record(Long shopId, Long productVariantId, EngagementKind kind, Long customerId) {
        if (shopId == null || productVariantId == null || kind == null) {
            return;
        }
        try {
            shopScope.within(shopId, () -> {
                CommerceMode mode = listings.findByProductVariantId(productVariantId)
                        .map(listing -> listing.getCommerceMode()).orElse(null);
                if (mode == null) {
                    // No such listing at that shop. Nothing to record, and
                    // nothing to tell the caller either - which listing ids a
                    // shop has is not a question this endpoint answers.
                    return null;
                }
                ListingEngagementEvent event = new ListingEngagementEvent();
                event.setProductVariantId(productVariantId);
                event.setKind(kind);
                event.setCommerceMode(mode);
                event.setCustomerId(customerId);
                event.setOccurredAt(LocalDateTime.now());
                // shop_id is stamped by TenantEntityListener from the scope.
                events.save(event);
                return null;
            });
        } catch (RuntimeException couldNotRecord) {
            // No customer identifier and no listing detail in the message.
            log.warn("Could not record listing engagement for shop {}: {}",
                    shopId, couldNotRecord.getClass().getSimpleName());
        }
    }

    /**
     * What this shop's listings attracted in a window.
     *
     * <p>Read inside the shop's own scope, and grouped in the database.
     */
    @Transactional(readOnly = true)
    public EngagementReport reportFor(Long shopId, LocalDateTime from, LocalDateTime to) {
        if (shopId == null || from == null || to == null || !from.isBefore(to)) {
            return EngagementReport.nothing(from, to);
        }
        return shopScope.within(shopId, () -> {
            Map<CommerceMode, Map<EngagementKind, Long>> byMode = new EnumMap<>(CommerceMode.class);
            for (Object[] row : events.countForShop(from, to)) {
                EngagementKind kind = (EngagementKind) row[0];
                CommerceMode mode = (CommerceMode) row[1];
                long count = ((Number) row[2]).longValue();
                byMode.computeIfAbsent(mode, m -> new EnumMap<>(EngagementKind.class))
                        .merge(kind, count, Long::sum);
            }

            Map<Long, Map<EngagementKind, Long>> byListing = new LinkedHashMap<>();
            for (Object[] row : events.countByListing(from, to)) {
                Long variantId = ((Number) row[0]).longValue();
                EngagementKind kind = (EngagementKind) row[1];
                long count = ((Number) row[3]).longValue();
                byListing.computeIfAbsent(variantId, v -> new EnumMap<>(EngagementKind.class))
                        .merge(kind, count, Long::sum);
            }

            return new EngagementReport(from, to, byMode, byListing);
        });
    }

    /**
     * What a merchant is shown, and what it is careful not to claim.
     *
     * @param note the sentence that keeps this honest. It travels WITH the
     *             numbers rather than living in a screen's copy, because a
     *             number without it invites exactly the reading that would
     *             make it a lie - and a second client would not have it.
     */
    public record EngagementReport(LocalDateTime from, LocalDateTime to,
                                   Map<CommerceMode, Map<EngagementKind, Long>> byMode,
                                   Map<Long, Map<EngagementKind, Long>> byListing,
                                   String note) {

        public EngagementReport(LocalDateTime from, LocalDateTime to,
                                Map<CommerceMode, Map<EngagementKind, Long>> byMode,
                                Map<Long, Map<EngagementKind, Long>> byListing) {
            this(from, to, byMode, byListing, NOTE);
        }

        public static final String NOTE =
                "These are the things customers did in the app: cards seen, "
                        + "listings opened, directions asked for, calls placed. "
                        + "GP-STORE does not know who walked into the shop, who "
                        + "bought, or what they paid, so none of these numbers is "
                        + "a sale or a share of one.";

        static EngagementReport nothing(LocalDateTime from, LocalDateTime to) {
            return new EngagementReport(from, to, Map.of(), Map.of(), NOTE);
        }

        /** Every kind added up, so a screen can lead with one number. */
        public long total() {
            long sum = 0;
            for (Map<EngagementKind, Long> kinds : byMode.values()) {
                for (Long count : kinds.values()) {
                    sum += count;
                }
            }
            return sum;
        }

        /** The listings that attracted the most interest, best first. */
        public List<Map.Entry<Long, Map<EngagementKind, Long>>> busiestListings(int howMany) {
            return byListing.entrySet().stream()
                    .sorted((a, b) -> Long.compare(sumOf(b.getValue()), sumOf(a.getValue())))
                    .limit(Math.max(howMany, 0))
                    .toList();
        }

        private static long sumOf(Map<EngagementKind, Long> kinds) {
            long sum = 0;
            for (Long count : kinds.values()) {
                sum += count;
            }
            return sum;
        }
    }
}
