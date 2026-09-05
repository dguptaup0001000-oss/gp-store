package com.gpstore.platform.api;

import com.gpstore.catalog.shop.ShopProductVariant;
import com.gpstore.catalog.shop.ShopProductVariantRepository;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.platform.*;
import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * A shopkeeper's own shop: its profile, its price list, and who is on its staff.
 *
 * THERE IS NO SHOP ID IN ANY PATH HERE, and that is the design rather than an
 * omission. Every route acts on "the shop this request is for", which
 * TenantContextFilter established from the credential before the controller
 * ran. A /api/shops/{id}/listings shape would put a shop id in front of a
 * caller, and then the only thing standing between a merchant and a
 * competitor's price list would be a check somebody has to remember to write
 * on every route.
 *
 * WHAT A SHOPKEEPER MAY CHANGE HERE. Their own listing rows: price, cost,
 * whether the item is on their shelf, their own shelf order. What they may NOT
 * change from here is what the product IS - its name, pack size, category,
 * photo - because that row is shared with every other shop selling it. That
 * lives under /api/products and is gated by CatalogDefinitionAuthorization.
 */
@RestController
@RequestMapping("/api/shop")
public class ShopSelfServiceController {

    private final ShopRepository shops;
    private final ShopLifecycleService shopLifecycle;
    private final ShopProductVariantRepository listings;
    private final ShopStaffRepository staff;
    private final CurrentUser currentUser;

    public ShopSelfServiceController(ShopRepository shops, ShopLifecycleService shopLifecycle,
                                     ShopProductVariantRepository listings,
                                     ShopStaffRepository staff, CurrentUser currentUser) {
        this.shops = shops;
        this.shopLifecycle = shopLifecycle;
        this.listings = listings;
        this.currentUser = currentUser;
        this.staff = staff;
    }

    public record ShopProfile(Long id, String code, String displayName, ShopStatus status,
                              String statusReason, Double latitude, Double longitude,
                              BigDecimal maxDeliveryRadiusKm, String timeZone,
                              String supportPhone, String supportEmail, String supportWhatsapp) {
        static ShopProfile of(Shop s) {
            return new ShopProfile(s.getId(), s.getCode(), s.getDisplayName(), s.getStatus(),
                    s.getStatusReason(), s.getLatitude(), s.getLongitude(),
                    s.getMaxDeliveryRadiusKm(), s.getTimeZone(),
                    s.getSupportPhone(), s.getSupportEmail(), s.getSupportWhatsapp());
        }
    }

    public record ProfileUpdate(String displayName, String supportPhone, String supportEmail,
                                String supportWhatsapp, Double latitude, Double longitude,
                                BigDecimal maxDeliveryRadiusKm, String timeZone) {}

    public record PauseRequest(String status, String reason) {}

    public record ListingView(Long id, Long productVariantId, BigDecimal sellingPrice,
                              BigDecimal costPrice, BigDecimal mrp, Boolean available,
                              Boolean active, Integer displayOrder) {
        static ListingView of(ShopProductVariant l) {
            return new ListingView(l.getId(), l.getProductVariantId(), l.getSellingPrice(),
                    l.getCostPrice(), l.getMrp(), l.getAvailable(), l.getActive(),
                    l.getDisplayOrder());
        }
    }

    public record ListingUpdate(BigDecimal sellingPrice, BigDecimal costPrice, BigDecimal mrp,
                                Boolean available, Boolean active, Integer displayOrder) {}

    /** The shop this request is for. Which shop that is came from the credential. */
    @GetMapping("/profile")
    public ShopProfile profile() {
        return ShopProfile.of(currentShop());
    }

    @PutMapping("/profile")
    public ShopProfile updateProfile(@RequestBody ProfileUpdate update) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        Shop shop = currentShop();

        // Status is NOT here. A shop pauses itself through /status below,
        // which goes through the transition table; letting a profile PUT carry
        // a status would be a second, unchecked way to change one.
        if (update.displayName() != null && !update.displayName().isBlank()) {
            shop.setDisplayName(update.displayName().trim());
        }
        if (update.supportPhone() != null) shop.setSupportPhone(blankToNull(update.supportPhone()));
        if (update.supportEmail() != null) shop.setSupportEmail(blankToNull(update.supportEmail()));
        if (update.supportWhatsapp() != null) shop.setSupportWhatsapp(blankToNull(update.supportWhatsapp()));
        if (update.latitude() != null) shop.setLatitude(update.latitude());
        if (update.longitude() != null) shop.setLongitude(update.longitude());
        if (update.maxDeliveryRadiusKm() != null) shop.setMaxDeliveryRadiusKm(update.maxDeliveryRadiusKm());
        if (update.timeZone() != null && !update.timeZone().isBlank()) {
            shop.setTimeZone(update.timeZone().trim());
        }
        return ShopProfile.of(shops.save(shop));
    }

    /**
     * The shopkeeper's own pause and reopen.
     *
     * Refuses anything else - lifting a platform suspension, or closing for
     * good - through ShopLifecycleService, so the rule holds whatever route
     * reaches it.
     */
    @PutMapping("/status")
    public ShopProfile setStatus(@RequestBody PauseRequest request) {
        requirePermission(AdminPermission.CATALOG_MANAGE);
        ShopStatus next;
        try {
            next = ShopStatus.valueOf(String.valueOf(request.status()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new BadRequestException("Unknown shop status: " + request.status());
        }
        return ShopProfile.of(shopLifecycle.transitionAsMerchant(
                currentShop().getId(), next, request.reason()));
    }

    // --------------------------------------------------------- the price list

    @GetMapping("/listings")
    public List<ListingView> listings(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "100") int size) {
        return listings.findAllByOrderByIdAsc(com.gpstore.config.PageRequests.of(page, size))
                .map(ListingView::of).toList();
    }

    /**
     * Sets what this shop charges for one catalogue item.
     *
     * THE SHOP IS NOT A PARAMETER. The row is found through the shop-scoped
     * repository and stamped by the tenant listener on insert, so this method
     * has no way to write into another shop even if a caller wanted it to.
     */
    @PutMapping("/listings/{productVariantId}")
    public ListingView upsertListing(@PathVariable Long productVariantId,
                                     @RequestBody ListingUpdate update) {
        if (update.sellingPrice() == null || update.sellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("A listing needs a selling price greater than 0.");
        }
        ShopProductVariant listing = listings.findByProductVariantId(productVariantId)
                .orElseGet(ShopProductVariant::new);
        listing.setProductVariantId(productVariantId);
        listing.setSellingPrice(update.sellingPrice());
        listing.setCostPrice(update.costPrice());
        listing.setMrp(update.mrp());
        listing.setAvailable(update.available() == null ? Boolean.TRUE : update.available());
        listing.setActive(update.active() == null ? Boolean.TRUE : update.active());
        listing.setDisplayOrder(update.displayOrder());
        return ListingView.of(listings.save(listing));
    }

    @DeleteMapping("/listings/{productVariantId}")
    public void delistItem(@PathVariable Long productVariantId) {
        ShopProductVariant listing = listings.findByProductVariantId(productVariantId)
                .orElseThrow(() -> new ResourceNotFoundException("This shop does not list that item"));
        listing.setAvailable(Boolean.FALSE);
        listing.setActive(Boolean.FALSE);
        listings.save(listing);
    }

    // -------------------------------------------------------------- the staff

    public record StaffView(Long customerId, Boolean isDefault, Boolean active) {}

    /** Who works here. Shop-scoped, so it can only ever be this shop's list. */
    @GetMapping("/staff")
    public List<StaffView> staff() {
        return staff.findAll().stream()
                .map(s -> new StaffView(s.getCustomerId(), s.getIsDefault(), s.getActive()))
                .toList();
    }

    private Shop currentShop() {
        Long shopId = TenantContext.require().requireShopId();
        return shops.findById(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
    }

    /**
     * A second check beside the route's.
     *
     * /api/shop/** is gated on CATALOG_VIEW so that read routes are open to
     * every staff role that can see the catalogue. The write routes need more,
     * and asking here keeps the two rules in the file that knows which is
     * which rather than in a path pattern nobody re-reads.
     */
    private void requirePermission(AdminPermission permission) {
        if (!currentUser.has(permission)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This account may not change the shop's " + permission.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
