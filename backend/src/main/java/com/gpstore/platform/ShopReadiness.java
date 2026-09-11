package com.gpstore.platform;

import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliverySubzoneRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.store.hours.ShopBusinessHoursRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * What still stands between this shop and its first order.
 *
 * WHY THIS EXISTS, and it is not a nicety. Walking a second merchant through
 * onboarding end to end, the thing that stopped them was not a missing
 * feature - every piece had an endpoint and every endpoint worked. It was that
 * when something was not ready, the only thing anyone was told was
 *
 *     "This shop is not currently taking orders."
 *
 * which is the right sentence to show a CUSTOMER - the platform's reasons are
 * between the platform and the merchant, and a suspension reason is nobody
 * else's business - and useless to the shopkeeper trying to work out what they
 * have not done yet. The real answer that day was that their merchant record
 * was APPROVED and not ACTIVE, which no screen anywhere showed.
 *
 * SO THIS IS THE SHOPKEEPER'S HALF OF THAT MESSAGE. Same facts, told to the
 * person who can act on them, and never to a customer.
 *
 * IT REPORTS, IT DOES NOT ENFORCE. Nothing here decides whether an order may
 * be placed - ShopTradingGate does, and it consults the shop and the merchant
 * exactly as it did before. A checklist that were also the gate would drift
 * into being the rule, and the rule belongs where the order is taken.
 */
@Service
public class ShopReadiness {

    private final ShopRepository shops;
    private final MerchantRepository merchants;
    private final DeliverySubzoneRepository subzones;
    private final DeliveryPartnerRepository riders;
    private final ShopProductVariantRepository listings;
    private final InventoryRepository inventory;
    private final ShopBusinessHoursRepository businessHours;

    public ShopReadiness(ShopRepository shops, MerchantRepository merchants,
                         DeliverySubzoneRepository subzones, DeliveryPartnerRepository riders,
                         ShopProductVariantRepository listings, InventoryRepository inventory,
                         ShopBusinessHoursRepository businessHours) {
        this.shops = shops;
        this.merchants = merchants;
        this.subzones = subzones;
        this.riders = riders;
        this.listings = listings;
        this.inventory = inventory;
        this.businessHours = businessHours;
    }

    /**
     * @param blocking true when this step stops orders outright, false when it
     *                 only makes them worse - an order still goes out without
     *                 a drawn territory, just to whoever is least loaded
     * @param done     whether the shop has done it
     * @param detail   what to do about it, in the shopkeeper's terms
     */
    public record Step(String name, boolean done, boolean blocking, String detail) {}

    public record Readiness(Long shopId, String displayName, boolean canTakeOrders,
                            List<Step> steps) {}

    @Transactional(readOnly = true)
    public Readiness forCurrentShop() {
        Long shopId = TenantContext.require().requireShopId();
        Shop shop = shops.findById(shopId)
                .orElseThrow(() -> new com.gpstore.exception.ResourceNotFoundException("Shop not found"));
        Merchant merchant = shop.getMerchantId() == null
                ? null : merchants.findById(shop.getMerchantId()).orElse(null);

        List<Step> steps = new ArrayList<>();

        // THE TWO SWITCHES, and the one that actually caught a real onboarding
        // out. APPROVED means the papers are in order; ACTIVE means the
        // business is trading. A shop can be perfectly set up and ACTIVE under
        // a merchant that is only APPROVED, and sell nothing, with no screen
        // anywhere saying why.
        boolean merchantTrading = merchant != null && merchant.getStatus() != null
                && merchant.getStatus().canTrade();
        steps.add(new Step("merchant-active", merchantTrading, true,
                merchantTrading
                        ? "The business is registered and trading."
                        : "The business is registered but not switched on yet"
                                + (merchant == null ? "" : " (it is " + merchant.getStatus() + ")")
                                + ". The platform has to activate it before anything can sell."));

        boolean shopOpen = shop.getStatus() != null && shop.getStatus().canAcceptOrders()
                && Boolean.TRUE.equals(shop.getActive());
        steps.add(new Step("shop-open", shopOpen, true,
                shopOpen
                        ? "The shop is open."
                        : "The shop is " + shop.getStatus() + ", so it is not taking orders."));

        boolean located = shop.getLatitude() != null && shop.getLongitude() != null;
        steps.add(new Step("shop-located", located, true,
                located
                        ? "Customers nearby can find this shop."
                        : "Set where the shop is. Without a pin nobody can find it - the "
                                + "marketplace matches customers to shops by distance, and a shop "
                                + "with no coordinates matches nobody."));

        boolean delivers = shop.getMaxDeliveryRadiusKm() != null
                && shop.getMaxDeliveryRadiusKm().signum() > 0;
        steps.add(new Step("delivery-radius", delivers, true,
                delivers
                        ? "Delivering up to " + shop.getMaxDeliveryRadiusKm() + " km."
                        : "Say how far this shop will deliver. Nobody is offered a shop that has "
                                + "not said."));

        long listed = listings.count();
        steps.add(new Step("has-listings", listed > 0, true,
                listed > 0
                        ? listed + " item(s) on the shelf."
                        : "Put something on the shelf. Products come from the shared catalogue - "
                                + "this shop sets its own price for each one it wants to sell."));

        long inStock = inventory.count();
        steps.add(new Step("has-stock", inStock > 0, true,
                inStock > 0
                        ? inStock + " item(s) with stock recorded."
                        : "Record how much stock there is. A listing with no stock behind it "
                                + "cannot be added to a basket."));

        // NOT BLOCKING, and saying so honestly matters. An order still goes
        // out with no map and no rider of its own - it falls back to whoever
        // is least loaded, recorded as FALLBACK. It goes out worse, which is a
        // different thing from not going out, and a checklist that called both
        // "blocked" would train people to ignore it.
        long territories = subzones.count();
        steps.add(new Step("has-territory", territories > 0, false,
                territories > 0
                        ? territories + " territory/territories drawn."
                        : "Draw at least one delivery territory. Orders still go out without "
                                + "one, but they go to whoever is free rather than to the rider "
                                + "who knows those streets."));

        long roster = riders.count();
        steps.add(new Step("has-riders", roster > 0, false,
                roster > 0
                        ? roster + " rider(s) on the roster."
                        : "Add at least one delivery worker. Riders belong to this shop and "
                                + "nobody else's."));

        // NOT BLOCKING, BECAUSE THE SHOP IS TRADING EITHER WAY - and that is
        // exactly why it needs saying. A shop that has never set its own week
        // is not shut: ShopHours.isConfigured() is false, so every day falls
        // back to the deployment's hours, and the shopkeeper is left running
        // on GP-STORE's clock without being told. That is fine for the shop
        // this platform grew out of, whose hours the deployment defaults ARE.
        // It is wrong for the second shop and every one after it, which is a
        // marketplace's problem and not a single shop's.
        //
        // THE HOURS ARE NOT CREATED FOR THEM. Opening a shop with seven
        // invented days would be a worse answer than the fallback: it would
        // read as a decision the shopkeeper made, and the first customer to
        // arrive at a shut shutter would be the one who found out otherwise.
        boolean ownHours = !businessHours.wholeWeek().isEmpty();
        steps.add(new Step("shop-hours", ownHours, false,
                ownHours
                        ? "This shop trades on its own hours."
                        : "Set this shop's opening hours. Until then it trades on GP-STORE's "
                                + "default hours, which are not this shop's - customers are "
                                + "offered delivery slots nobody here agreed to."));

        boolean blocked = steps.stream().anyMatch(s -> s.blocking() && !s.done());
        return new Readiness(shop.getId(), shop.getDisplayName(), !blocked, steps);
    }
}
