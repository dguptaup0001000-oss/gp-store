package com.gpstore.pricing;

import com.gpstore.entity.Address;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.DeliveryPricingSettings;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.DeliveryPricingSettingsRepository;
import com.gpstore.service.DeliveryEstimateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The delivery price for a real order: gathers the inputs, hands them to the
 * calculator, and reports what it had to assume.
 *
 * THE SPLIT IS THE DESIGN. Everything that needs a database - what the order
 * weighs, what it cost the shop, how far the address is - happens here.
 * The rule itself lives in {@link DeliveryPricingCalculator}, which takes
 * numbers and returns numbers, so every case in the brief is a unit test
 * rather than a fixture.
 *
 * THIS IS THE ONLY PLACE A DELIVERY PRICE IS DECIDED. The app displays what it
 * is given and is never asked what anything costs - §13 of the brief, and the
 * reason is obvious once stated: a client that can name a price can name zero.
 */
@Service
public class DeliveryPricingService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPricingService.class);

    private final DeliveryPricingSettingsRepository settingsRepository;
    private final DeliveryEstimateService estimateService;
    private final com.gpstore.catalog.shop.ShopCatalog shopCatalog;
    private final DeliveryPricingService self;

    public DeliveryPricingService(DeliveryPricingSettingsRepository settingsRepository,
                                  DeliveryEstimateService estimateService,
                                  com.gpstore.catalog.shop.ShopCatalog shopCatalog,
                                  @org.springframework.context.annotation.Lazy DeliveryPricingService self) {
        this.settingsRepository = settingsRepository;
        this.estimateService = estimateService;
        this.shopCatalog = shopCatalog;
        this.self = self;
    }

    /**
     * The current settings, creating the V1 row if this is a database the
     * migration has not reached.
     *
     * CACHED, because this is on the checkout path. Every checkout preview and
     * every placed order asks for these, preview runs on every cart change,
     * and without a cache that is one database round trip per keystroke-ish
     * event on a pool of ten connections. The row changes when an
     * administrator edits a price - a few times a year - so re-reading it per
     * request buys nothing and costs a connection at exactly the moment
     * connections are scarce.
     *
     * The @CacheEvict on save() is what keeps an edit visible immediately.
     *
     * Never returns null and never throws. The pricing path runs inside
     * checkout, and a settings read that failed would take the shop offline
     * over a configuration table - the defaults on the entity are the same V1
     * numbers the migration inserts.
     */
    @Cacheable(value = "deliveryPricingSettings", unless = "#result == null")
    @Transactional
    public DeliveryPricingSettings settings() {
        try {
            // Per shop (V49). The @Cacheable key carries the shop too - see
            // CacheConfig.keyGenerator - so one shop's delivery pricing can
            // never be served to another out of the cache.
            DeliveryPricingSettings found = settingsRepository
                    .findByShopId(com.gpstore.platform.TenantDefaults
                            .shopIdForCurrentWork(DeliveryPricingSettings.class))
                    .orElse(null);
            if (found != null) {
                found.normalise();
                return found;
            }
            DeliveryPricingSettings created = new DeliveryPricingSettings();
            created.setUpdatedAt(LocalDateTime.now());
            created.setUpdatedBy("auto-created on first pricing call");
            return settingsRepository.save(created);
        } catch (Exception e) {
            log.error("Could not read delivery pricing settings; using built-in V1 defaults for "
                    + "this request. Delivery is still being priced, but any admin edits are "
                    + "NOT being applied.", e);
            DeliveryPricingSettings fallback = new DeliveryPricingSettings();
            fallback.normalise();
            return fallback;
        }
    }

    @CacheEvict(value = "deliveryPricingSettings", allEntries = true)
    @Transactional
    public DeliveryPricingSettings save(DeliveryPricingSettings incoming, String who) {
        // Edits this shop's row, or creates it. The id comes from the row that
        // is already there rather than from the request body: without this an
        // admin could post an id and rewrite ANOTHER shop's delivery pricing,
        // which is the same id-manipulation attack the rest of Slice 1 closes -
        // and a body-supplied id would slip past the read filter because a
        // save() by id is not a query.
        Long shopId = com.gpstore.platform.TenantDefaults
                .shopIdForCurrentWork(DeliveryPricingSettings.class);
        incoming.setId(settingsRepository.findByShopId(shopId)
                .map(DeliveryPricingSettings::getId)
                .orElse(null));
        incoming.setShopId(shopId);
        incoming.normalise();
        incoming.setUpdatedAt(LocalDateTime.now());
        incoming.setUpdatedBy(who);
        return settingsRepository.save(incoming);
    }

    // ------------------------------------------------------------ quoting

    /** Prices a cart, at checkout preview time. */
    @Transactional(readOnly = true)
    public DeliveryQuote quoteForCart(List<CartItem> cartItems, Address address) {
        List<OrderWeightCalculator.Line> lines = new ArrayList<>();
        for (CartItem item : cartItems) {
            lines.add(new OrderWeightCalculator.Line(
                    item.getProductVariant(), item.getQuantity() == null ? 0 : item.getQuantity()));
        }
        return quote(lines, address);
    }

    /** Prices an order's own items, for the admin breakdown after the fact. */
    @Transactional(readOnly = true)
    public DeliveryQuote quoteForOrderItems(List<OrderItem> orderItems, Address address) {
        List<OrderWeightCalculator.Line> lines = new ArrayList<>();
        for (OrderItem item : orderItems) {
            lines.add(new OrderWeightCalculator.Line(
                    item.getProductVariant(), item.getQuantity() == null ? 0 : item.getQuantity()));
        }
        return quote(lines, address);
    }

    private DeliveryQuote quote(List<OrderWeightCalculator.Line> lines, Address address) {
        DeliveryPricingSettings s = self.settings();

        List<String> warnings = new ArrayList<>();

        // ---- distance -------------------------------------------------
        BigDecimal km = null;
        boolean estimated = true;
        if (address != null) {
            double straightLine = estimateService.distanceFromStoreKm(
                    address.getLatitude(), address.getLongitude());
            if (!Double.isNaN(straightLine)) {
                // ROAD DISTANCE IS WHAT THE RULE WANTS and this deployment has
                // no routing provider - there is no maps API key configured and
                // the network is closed. The straight line is scaled by the
                // configured factor, which ships at 1.000 so nobody is charged
                // for kilometres nobody measured. Marked estimated so the admin
                // breakdown says so rather than implying a measured route.
                km = BigDecimal.valueOf(straightLine).multiply(s.getRoadDistanceFactor());
            }
        }
        if (km == null) {
            warnings.add("This address has no usable coordinates, so the distance could not be "
                    + "measured.");
        } else if (s.getRoadDistanceFactor().compareTo(BigDecimal.ONE) == 0) {
            warnings.add("Distance is straight-line, not road distance - no routing provider is "
                    + "configured. Real road distance is always longer, so this under-charges.");
        }

        // ---- weight ---------------------------------------------------
        OrderWeightCalculator.WeightResult weight = OrderWeightCalculator.totalWeightKg(lines, s);
        warnings.addAll(weight.warnings());

        // ---- margin ---------------------------------------------------
        ProfitResult profit = grossProfit(lines);
        warnings.addAll(profit.warnings());

        DeliveryQuote quote = DeliveryPricingCalculator.quote(
                s, km, estimated, weight.totalKg(), profit.profit(), warnings);

        if (quote.hasWarnings()) {
            // Logged as well as carried. The quote reaches an admin screen only
            // if somebody opens it; the log reaches whoever is watching the
            // service, and §12 asks for missing financial data to be surfaced
            // rather than assumed away.
            log.warn("Delivery quote computed with {} caveat(s): {}",
                    quote.warnings().size(), String.join(" | ", quote.warnings()));
        }
        return quote;
    }

    // ------------------------------------------------------------- margin

    private record ProfitResult(BigDecimal profit, List<String> warnings) {
    }

    /**
     * Gross margin: sum of (selling - cost) x quantity.
     *
     * AN ITEM WITH NO COST PRICE CONTRIBUTES NOTHING, and that is the whole
     * point rather than a shortcut. Treating unknown cost as zero would make
     * the item look pure profit and hand out free delivery on an order the
     * shop cannot actually afford to deliver. Zero margin is the conservative
     * reading, and every such item is named so the gap gets filled.
     *
     * §8 of the brief in one sentence: this is why a ₹1,000 order does not
     * automatically get free delivery. What matters is the margin, not the
     * total.
     */
    private ProfitResult grossProfit(List<OrderWeightCalculator.Line> lines) {
        BigDecimal total = BigDecimal.ZERO;
        List<String> missing = new ArrayList<>();

        // The shop's own price and cost, not the catalogue's - free delivery is
        // paid for out of the margin this shop actually made.
        java.util.Map<Long, com.gpstore.catalog.shop.ShopProductVariant> listings =
                shopCatalog.listingsFor(lines.stream()
                        .map(l -> l.variant() == null ? null : l.variant().getId())
                        .toList());

        for (OrderWeightCalculator.Line line : lines) {
            ProductVariant v = line.variant();
            if (v == null || line.quantity() <= 0) {
                continue;
            }
            com.gpstore.catalog.shop.ShopProductVariant listing = listings.get(v.getId());
            BigDecimal selling = listing != null ? listing.getSellingPrice() : v.getSellingPrice();
            BigDecimal cost = listing != null && listing.getCostPrice() != null
                    ? listing.getCostPrice() : v.getCostPrice();

            if (selling == null || cost == null) {
                String label = v.getSku() != null && !v.getSku().isBlank()
                        ? v.getSku() : "variant " + v.getId();
                if (!missing.contains(label)) {
                    missing.add(label);
                }
                continue;
            }
            total = total.add(selling.subtract(cost).multiply(BigDecimal.valueOf(line.quantity())));
        }

        List<String> warnings = new ArrayList<>();
        if (!missing.isEmpty()) {
            List<String> shown = missing.size() > 8 ? missing.subList(0, 8) : missing;
            warnings.add("No cost price is recorded for " + missing.size() + " item(s), so they "
                    + "contributed no margin and this order is LESS likely to qualify for free "
                    + "delivery than it should be: " + String.join(", ", shown)
                    + (missing.size() > shown.size() ? ", and others" : "")
                    + ". Set cost prices on these variants.");
        }

        return new ProfitResult(total, warnings);
    }
}
