package com.gpstore.catalog.shop;

import com.gpstore.discovery.PreferredShops;
import com.gpstore.entity.Address;
import com.gpstore.platform.ShopDiscovery;
import com.gpstore.platform.ShopScopeSwitch;
import com.gpstore.pricing.DeliveryPricingService;
import com.gpstore.repository.ProductVariantRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which shop supplies this product when the customer taps ADD.
 *
 * <h2>The question this answers</h2>
 *
 * <p>On a marketplace feed the customer chose a PRODUCT, not a shop. Several
 * shops near them may sell it at different prices with different delivery
 * charges, and one of them may be the shop this customer already told us they
 * prefer for this kind of thing. Something has to decide, and the decision has
 * to be defensible to both sides: the customer must not overpay, and a
 * merchant must not lose a sale to a rule nobody can explain.
 *
 * <h2>What it does NOT do</h2>
 *
 * <p>NOT BLINDLY CHEAPEST. A product price is not a cost. A shop 200 metres
 * away at ₹42 with free delivery beats one across town at ₹40 plus ₹30 to
 * bring it, and sorting on the price tag would recommend the second while
 * appearing to save money.
 *
 * <p>NOT BLINDLY NEAREST either. The closest shop charging a third more is
 * not a service to anybody, and "nearest" is the rule that quietly gives one
 * shop on a busy street every order in the neighbourhood.
 *
 * <p>NOT A PLACE TO SELL POSITION. Nothing here reads a payment, a promotion
 * or a subscription. If sponsored placement is ever added it must be a
 * visibly separate, labelled thing - never a thumb on this scale.
 *
 * <h2>The customer's own choice wins</h2>
 *
 * <p>A preferred shop is not one signal among several to be outvoted by two
 * rupees. The customer already made this decision and said so; the only
 * question left is whether that shop can actually serve them today. If it
 * can, it wins. If it cannot - closed, out of range, does not stock it - the
 * fallback is the ranking below rather than a failure, because a customer
 * whose usual shop is shut still wants their milk.
 */
@Service
public class SellerResolution {

    /**
     * How many shops are costed properly.
     *
     * <p>A delivery quote is per shop - it reads that shop's own pricing
     * settings inside that shop's scope - so costing every candidate is a
     * loop, and a loop over shops is the shape that cost this application its
     * ceiling once before. Bounding the candidates first means the loop can
     * never grow with the marketplace: it is at most this many however many
     * shops open. Eight is comfortably more than a customer will read, and the
     * ones dropped are the farthest, which are the least likely to win on
     * total cost anyway.
     */
    private static final int COSTED_CANDIDATES = 8;

    private final ShopDiscovery discovery;
    private final PreferredShops preferredShops;
    private final ShopScopeSwitch shopScope;
    private final DeliveryPricingService deliveryPricing;
    private final ProductVariantRepository variants;
    private final JdbcTemplate jdbc;

    public SellerResolution(ShopDiscovery discovery,
                            PreferredShops preferredShops,
                            ShopScopeSwitch shopScope,
                            DeliveryPricingService deliveryPricing,
                            ProductVariantRepository variants,
                            JdbcTemplate jdbc) {
        this.discovery = discovery;
        this.preferredShops = preferredShops;
        this.shopScope = shopScope;
        this.deliveryPricing = deliveryPricing;
        this.variants = variants;
        this.jdbc = jdbc;
    }

    /**
     * Every nearby shop that can supply this variant online, best first.
     *
     * <p>Returns a list rather than one answer on purpose. The app shows a
     * chooser when there is a real choice to make, and takes the head of the
     * list when the customer would rather not think about it - one method,
     * both behaviours, and no chance of the chooser and the automatic pick
     * disagreeing about which shop is best.
     */
    @Transactional(readOnly = true)
    public List<SellerOption> sellersFor(Long customerId, Long productVariantId,
                                         Double lat, Double lng, Address address) {
        if (productVariantId == null || lat == null || lng == null) {
            return List.of();
        }

        Map<Long, Double> distanceByShop = new HashMap<>();
        for (ShopDiscovery.NearbyShop near : discovery.shopsServing(lat, lng)) {
            distanceByShop.put(near.shop().getId(), near.distanceKm());
        }
        if (distanceByShop.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = listingsOf(productVariantId, distanceByShop);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Nearest first, then bounded - see COSTED_CANDIDATES.
        candidates.sort(Comparator.comparingDouble(c -> c.distanceKm));
        if (candidates.size() > COSTED_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, COSTED_CANDIDATES));
        }

        List<Long> preferredForCategory = preferredFor(customerId, productVariantId);

        List<SellerOption> options = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            BigDecimal delivery = deliveryFeeAt(candidate.shopId, address);
            options.add(new SellerOption(
                    candidate.shopId,
                    candidate.shopName,
                    candidate.distanceKm,
                    candidate.price,
                    delivery,
                    candidate.price.add(delivery),
                    preferredForCategory.contains(candidate.shopId),
                    false,
                    productVariantId));
        }

        options.sort(ranking(preferredForCategory));
        return List.copyOf(options);
    }

    /** The shop ADD should use, when the customer is not being asked to choose. */
    @Transactional(readOnly = true)
    public java.util.Optional<SellerOption> bestSellerFor(Long customerId, Long productVariantId,
                                                          Double lat, Double lng, Address address) {
        List<SellerOption> options = sellersFor(customerId, productVariantId, lat, lng, address);
        return options.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(options.get(0));
    }

    /**
     * The order the customer sees, and the order ADD follows.
     *
     * <p>Preference first, because the customer already decided. Then total
     * cost, which is what they actually pay. Then distance, which breaks ties
     * towards the shop that will arrive sooner and keeps trade local. Then the
     * shop id, so the answer is stable - an order that changes between the
     * chooser opening and ADD being tapped is a bug the customer experiences
     * as the wrong shop.
     */
    private static Comparator<SellerOption> ranking(List<Long> preferred) {
        return Comparator
                .comparing((SellerOption o) -> !o.preferred())
                .thenComparing(o -> preferredRank(preferred, o.shopId()))
                .thenComparing(SellerOption::totalCost)
                .thenComparing(o -> o.distanceKm() == null ? Double.MAX_VALUE : o.distanceKm())
                .thenComparing(SellerOption::shopId);
    }

    /** First choice beats second choice, which is what the slots mean. */
    private static int preferredRank(List<Long> preferred, Long shopId) {
        int index = preferred.indexOf(shopId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * What this shop would charge to bring it.
     *
     * <p>Inside the shop's own scope, because delivery pricing is a shop's own
     * settings and reading it anywhere else would quote one merchant's rates
     * for another's van. FAILS SOFT: a shop whose pricing cannot be read is
     * costed at zero delivery rather than dropped, because dropping it hides a
     * seller the customer might have wanted over an internal problem they
     * cannot see. The quote is an estimate for ranking; checkout re-prices
     * properly and is the number that binds.
     */
    private BigDecimal deliveryFeeAt(Long shopId, Address address) {
        if (address == null) {
            return BigDecimal.ZERO;
        }
        try {
            return shopScope.within(shopId, () -> {
                var quote = deliveryPricing.quoteForCart(List.of(), address);
                return quote == null || quote.finalCharge() == null
                        ? BigDecimal.ZERO : quote.finalCharge();
            });
        } catch (RuntimeException couldNotQuote) {
            return BigDecimal.ZERO;
        }
    }

    /** This customer's chosen shops for the category this product sits in. */
    private List<Long> preferredFor(Long customerId, Long productVariantId) {
        if (customerId == null) {
            return List.of();
        }
        Long categoryId = variants.findByIdWithProduct(productVariantId)
                .map(v -> v.getProduct() == null || v.getProduct().getCategory() == null
                        ? null : v.getProduct().getCategory().getId())
                .orElse(null);
        return categoryId == null ? List.of() : preferredShops.forCategory(customerId, categoryId);
    }

    /**
     * Which of these shops list this variant for online sale.
     *
     * <p>Native and explicitly narrowed for the reason the marketplace feed
     * is: a filtered query could only answer about one shop, which is the
     * opposite of the question. ONLY ONLINE_PURCHASE - a Visit-to-Buy listing
     * is not a seller of anything the cart can hold, and offering one here
     * would route a customer towards a purchase the backend then refuses.
     */
    private List<Candidate> listingsOf(Long productVariantId, Map<Long, Double> distanceByShop) {
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(distanceByShop.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(productVariantId);
        args.addAll(distanceByShop.keySet());

        return jdbc.query(
                "SELECT spv.shop_id, s.display_name, spv.selling_price "
                        + "FROM shop_product_variants spv "
                        + "JOIN shops s ON s.id = spv.shop_id "
                        + "WHERE spv.product_variant_id = ? "
                        + "AND spv.shop_id IN (" + placeholders + ") "
                        + "AND spv.commerce_mode = 'ONLINE_PURCHASE' "
                        + "AND spv.available = true "
                        + "AND COALESCE(spv.active, true) = true "
                        + "AND spv.selling_price IS NOT NULL AND spv.selling_price > 0",
                (rs, rowNum) -> new Candidate(
                        rs.getLong("shop_id"),
                        rs.getString("display_name"),
                        rs.getBigDecimal("selling_price"),
                        distanceByShop.getOrDefault(rs.getLong("shop_id"), Double.MAX_VALUE)),
                args.toArray());
    }

    private record Candidate(Long shopId, String shopName, BigDecimal price, double distanceKm) {}
}
