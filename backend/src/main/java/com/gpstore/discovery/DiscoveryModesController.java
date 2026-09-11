package com.gpstore.discovery;

import com.gpstore.platform.CustomerOwnedRead;
import com.gpstore.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The customer's two ways of finding a shop (Part 2 §3).
 *
 * <p>TWO MODES, BOTH THE CUSTOMER'S CHOICE. "My preferred shops" orders by
 * what they have already decided; "best deal" orders by what this purchase
 * would cost. Neither is the default the other has to argue with, and §4 is
 * explicit that the platform must not silently swap one for the other
 * because a cheaper shop turned up.
 *
 * <p>SEPARATE FROM /api/marketplace, which is public. These routes read the
 * signed-in customer's own preferences, so they need a customer - and putting
 * them under the public prefix would either leak preferences or require a
 * hole in a rule that is currently simple.
 */
@RestController
@RequestMapping("/api/discovery")
public class DiscoveryModesController {

    private final ShopOffers offers;
    private final BestDeal bestDeal;
    private final PriceGapRule priceGap;
    private final PreferredShops preferred;
    private final CurrentUser currentUser;
    private final CustomerOwnedRead customerOwnedRead;

    public DiscoveryModesController(ShopOffers offers, BestDeal bestDeal, PriceGapRule priceGap,
                                    PreferredShops preferred, CurrentUser currentUser,
                                    CustomerOwnedRead customerOwnedRead) {
        this.offers = offers;
        this.bestDeal = bestDeal;
        this.priceGap = priceGap;
        this.preferred = preferred;
        this.currentUser = currentUser;
        this.customerOwnedRead = customerOwnedRead;
    }

    /**
     * COMPARE OTHER SHOPS (§8): the same variant, priced by everybody in reach.
     *
     * <p>Ordered by Best Deal, and the response says the final payable for
     * each - §8: "the primary comparison should make FINAL PAYABLE amount
     * obvious", and §7: "do not hide delivery charges until checkout".
     *
     * <p>It also answers §7's question directly rather than leaving the app
     * to do the arithmetic: is there a farther shop cheap enough to be worth
     * leaving the neighbourhood for?
     */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam Long variantId,
                                       @RequestParam(required = false) Double lat,
                                       @RequestParam(required = false) Double lng,
                                       @RequestParam(required = false) BigDecimal radiusKm) {

        List<ShopOffer> found = customerOwnedRead.acrossShops(
                () -> offers.forVariant(variantId, lat, lng, radiusKm));
        List<ShopOffer> ranked = bestDeal.rank(found);

        ShopOffer nearest = bestDeal.nearestBuyable(ranked);
        ShopOffer cheapest = ranked.stream().filter(ShopOffer::isBuyableNow).findFirst().orElse(null);

        boolean fartherIsWorthIt = nearest != null && cheapest != null
                && !nearest.shopId().equals(cheapest.shopId())
                && priceGap.qualifies(nearest.finalPayable(), cheapest.finalPayable());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variantId", variantId);
        body.put("offers", ranked);
        body.put("nearestShopId", nearest == null ? null : nearest.shopId());
        body.put("nearestFinalPayable", nearest == null ? null : nearest.finalPayable());
        body.put("cheapestShopId", cheapest == null ? null : cheapest.shopId());
        body.put("cheapestFinalPayable", cheapest == null ? null : cheapest.finalPayable());
        // §7, answered rather than implied.
        body.put("priceGapMultiplier", priceGap.multiplier());
        body.put("fartherSellerQualifies", fartherIsWorthIt);
        body.put("saving", nearest == null || cheapest == null
                ? BigDecimal.ZERO
                : priceGap.saving(nearest.finalPayable(), cheapest.finalPayable()));
        return body;
    }

    /**
     * MY PREFERRED SHOPS (§4): the same offers, the customer's order.
     *
     * <p>THE OTHER SHOPS ARE STILL IN THE LIST. §4 requires that a customer
     * in this mode can still see, compare, switch and buy elsewhere, so this
     * REORDERS and never filters - the preferred shops come first, everything
     * else follows in the order it already had, which is distance.
     *
     * <p>NOTHING HERE SECOND-GUESSES THE CUSTOMER. If their first choice is
     * dearer, it is still their first choice; the price is on the row for
     * them to see, and Best Deal is one tap away if they want it.
     */
    @GetMapping("/preferred")
    public Map<String, Object> preferredFirst(@RequestParam Long variantId,
                                              @RequestParam Long categoryId,
                                              @RequestParam(required = false) Double lat,
                                              @RequestParam(required = false) Double lng,
                                              @RequestParam(required = false) BigDecimal radiusKm) {
        Long me = currentUser.customerId();
        List<ShopOffer> found = customerOwnedRead.acrossShops(
                () -> offers.forVariant(variantId, lat, lng, radiusKm));

        List<Long> chosen = customerOwnedRead.acrossShops(
                () -> preferred.forCategory(me, categoryId));
        List<ShopOffer> ordered = customerOwnedRead.acrossShops(
                () -> preferred.preferredFirst(me, categoryId, found, ShopOffer::shopId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variantId", variantId);
        body.put("categoryId", categoryId);
        body.put("preferredShopIds", chosen);
        body.put("offers", ordered);
        // The honest label for an empty preference: this is distance order,
        // not a preference the customer forgot they set.
        body.put("hasPreference", !chosen.isEmpty());
        return body;
    }

    /**
     * §15: what a preferred shop does not have, and who else has it.
     *
     * <p>TWO OPTIONS, AND THE CUSTOMER PICKS. §15 is explicit that the system
     * must not quietly move the item: it may say "Shop D has this", and the
     * customer decides whether to keep waiting on their own shop or to let
     * that become a second shop order.
     */
    @GetMapping("/elsewhere")
    public Map<String, Object> elsewhere(@RequestParam Long variantId,
                                         @RequestParam Long fromShopId,
                                         @RequestParam(required = false) Double lat,
                                         @RequestParam(required = false) Double lng,
                                         @RequestParam(required = false) BigDecimal radiusKm) {
        List<ShopOffer> found = customerOwnedRead.acrossShops(
                () -> offers.forVariant(variantId, lat, lng, radiusKm));

        ShopOffer here = found.stream()
                .filter(o -> o.shopId().equals(fromShopId))
                .findFirst().orElse(null);

        List<ShopOffer> alternatives = bestDeal.rank(found.stream()
                .filter(o -> !o.shopId().equals(fromShopId))
                .filter(ShopOffer::isBuyableNow)
                .toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variantId", variantId);
        body.put("fromShopId", fromShopId);
        body.put("availableAtPreferredShop", here != null && here.isBuyableNow());
        body.put("listedAtPreferredShop", here != null);
        body.put("alternatives", alternatives);
        // Neither option is preselected. §15 leaves the choice with the
        // customer, and a default here would be the system making it.
        body.put("options", List.of("KEEP_WITH_THIS_SHOP", "BUY_ELSEWHERE"));
        return body;
    }
}
