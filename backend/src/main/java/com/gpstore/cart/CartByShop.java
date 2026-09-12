package com.gpstore.cart;

import com.gpstore.entity.Address;
import com.gpstore.entity.Cart;
import com.gpstore.entity.CartItem;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopScopeSwitch;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.pricing.DeliveryPricingService;
import com.gpstore.pricing.DeliveryQuote;
import com.gpstore.service.AddressService;
import com.gpstore.service.CartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One basket, drawn as the several purchases it actually is (Part 2 §13).
 *
 * <p>THE DANGER THIS EXISTS TO PREVENT is a screen that adds everything up
 * into one number. A customer who sees "Total ₹740" reasonably concludes they
 * are making one purchase from one seller, and they are not: they are about
 * to place three orders with three independent kiranas, each of which will
 * pack, price, deliver, bill and — if it goes wrong — refund separately.
 * §13 therefore asks for a per-shop subtotal, delivery charge, discount and
 * shop total, and says the combined figure "may be displayed informationally"
 * but "must NOT look like one combined merchant checkout".
 *
 * <p>SO THE COMBINED NUMBER IS NAMED FOR WHAT IT IS. The field is called
 * {@code informationalCombinedTotal}, not {@code total}, and it carries
 * {@code isSinglePayment: false} beside it. A field named "total" on a cart
 * response is an invitation to draw it large and put a Pay button under it.
 *
 * <p>DELIVERY IS QUOTED PER SHOP, by that shop, inside its own scope. Each
 * kirana prices its own delivery (§12, and Part 3 §3), so there is no single
 * delivery charge for a multi-shop basket and nothing here tries to invent
 * one.
 */
@Service
public class CartByShop {

    private final CartService carts;
    private final AddressService addresses;
    private final ShopRepository shops;
    private final ShopScopeSwitch shopScope;
    private final DeliveryPricingService deliveryPricing;

    public CartByShop(CartService carts, AddressService addresses, ShopRepository shops,
                      ShopScopeSwitch shopScope, DeliveryPricingService deliveryPricing) {
        this.carts = carts;
        this.addresses = addresses;
        this.shops = shops;
        this.shopScope = shopScope;
        this.deliveryPricing = deliveryPricing;
    }

    /**
     * One shop's part of the basket, priced on its own terms.
     *
     * @param deliveryKnown false when there is no address yet, or this shop
     *                      could not quote. Zero would read as free delivery,
     *                      which is a different and untrue statement.
     */
    public record ShopSection(Long shopId, String shopName, String logoUrl,
                              List<Line> lines,
                              int itemCount,
                              BigDecimal subtotal,
                              BigDecimal discount,
                              BigDecimal deliveryCharge,
                              boolean deliveryKnown,
                              BigDecimal shopTotal,
                              List<String> notes) {}

    public record Line(Long cartItemId, Long variantId, Long productId, String productName,
                       int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    /**
     * @param informationalCombinedTotal deliberately not called "total" -
     *                                   see the class comment
     * @param isSinglePayment            always false for more than one shop,
     *                                   and stated rather than left to be
     *                                   inferred from the section count
     */
    public record Basket(List<ShopSection> shops,
                         int shopCount,
                         BigDecimal informationalCombinedTotal,
                         boolean isSinglePayment,
                         boolean deliveryFullyKnown,
                         String note) {}

    /**
     * The basket, split.
     *
     * @param addressId where it would go, or null - without one, no shop can
     *                  quote delivery and every section says so
     */
    @Transactional(readOnly = true)
    public Basket forCustomer(Long customerId, Long addressId) {
        Cart cart = carts.getCustomerCart(customerId);
        List<CartItem> items = cart == null || cart.getItems() == null
                ? List.of() : cart.getItems();

        Address address = null;
        if (addressId != null) {
            // OWNERSHIP-CHECKED, because an address id arrives from a client
            // and quoting delivery to somebody else's house would confirm
            // that house exists.
            address = addresses.getOwnedAddress(addressId, customerId);
        }

        Map<Long, List<CartItem>> byShop = new LinkedHashMap<>();
        for (CartItem item : items) {
            byShop.computeIfAbsent(item.getShopId(), s -> new ArrayList<>()).add(item);
        }

        List<ShopSection> sections = new ArrayList<>();
        BigDecimal combined = BigDecimal.ZERO;
        boolean allDeliveryKnown = true;

        for (Map.Entry<Long, List<CartItem>> entry : byShop.entrySet()) {
            ShopSection section = section(entry.getKey(), entry.getValue(), address);
            sections.add(section);
            combined = combined.add(section.shopTotal());
            allDeliveryKnown = allDeliveryKnown && section.deliveryKnown();
        }

        return new Basket(
                List.copyOf(sections),
                sections.size(),
                combined,
                // ONE SHOP IS STILL ONE PAYMENT; two or more never are. Said
                // as a field so no client has to work it out from the list
                // length and get it wrong on the empty case.
                sections.size() <= 1,
                allDeliveryKnown,
                sections.size() > 1
                        ? "This basket will be placed as " + sections.size()
                          + " separate orders, one per shop. Each shop bills, delivers and "
                          + "refunds on its own."
                        : null);
    }

    private ShopSection section(Long shopId, List<CartItem> lines, Address address) {
        Shop shop = shopId == null ? null : TenantContext.runWithin(TenantScope.platform(),
                () -> shops.findById(shopId).orElse(null));

        List<Line> rendered = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int count = 0;
        for (CartItem item : lines) {
            BigDecimal lineTotal = item.getTotalPrice() == null ? BigDecimal.ZERO : item.getTotalPrice();
            subtotal = subtotal.add(lineTotal);
            count += item.getQuantity() == null ? 0 : item.getQuantity();
            var variant = item.getProductVariant();
            var product = variant == null ? null : variant.getProduct();
            rendered.add(new Line(
                    item.getId(),
                    variant == null ? null : variant.getId(),
                    product == null ? null : product.getId(),
                    product == null ? null : product.getName(),
                    item.getQuantity() == null ? 0 : item.getQuantity(),
                    item.getPrice(),
                    lineTotal));
        }

        BigDecimal delivery = BigDecimal.ZERO;
        boolean deliveryKnown = false;
        List<String> notes = new ArrayList<>();

        if (address == null) {
            notes.add("Delivery is quoted once you choose an address.");
        } else if (shopId == null) {
            notes.add("These items are not attached to a shop yet.");
        } else {
            try {
                // THIS SHOP PRICES ITS OWN DELIVERY, inside its own scope.
                DeliveryQuote quote = shopScope.within(shopId,
                        () -> deliveryPricing.quoteForCart(lines, address));
                if (quote != null && quote.finalCharge() != null) {
                    delivery = quote.finalCharge();
                    deliveryKnown = true;
                    if (quote.freeDelivery()) {
                        notes.add("Free delivery from this shop.");
                    }
                }
            } catch (RuntimeException cannotQuote) {
                // NOT ZERO. A shop that cannot quote has not offered free
                // delivery, and showing zero would understate the basket.
                notes.add("This shop could not quote delivery to that address.");
            }
        }

        return new ShopSection(
                shopId,
                shop == null ? null : shop.getDisplayName(),
                shop == null ? null : shop.getLogoUrl(),
                List.copyOf(rendered),
                count,
                subtotal,
                BigDecimal.ZERO,
                deliveryKnown ? delivery : null,
                deliveryKnown,
                subtotal.add(deliveryKnown ? delivery : BigDecimal.ZERO),
                List.copyOf(notes));
    }
}
