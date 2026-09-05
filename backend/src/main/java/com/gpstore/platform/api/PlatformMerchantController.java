package com.gpstore.platform.api;

import com.gpstore.platform.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * The marketplace operator's console: merchants and storefronts.
 *
 * EVERY ROUTE UNDER /api/platform/** NEEDS PERM_PLATFORM_ADMIN, which only
 * Role.PLATFORM_ADMIN holds (SecurityConfig). That is the whole authorization
 * story for this file and it is deliberately not repeated per method - a
 * per-method check is a per-method chance to forget one.
 *
 * A SHOPKEEPER CANNOT REACH ANY OF IT, including the shop they own. Their own
 * shop's profile and price list live under /api/shop/**, scoped to them by the
 * tenant filter. Requirement 8 in one sentence: running the market is a
 * different job from running a shop, and it has different routes.
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformMerchantController {

    private final MerchantLifecycleService merchantLifecycle;
    private final ShopLifecycleService shopLifecycle;
    private final ShopMembership membership;

    public PlatformMerchantController(MerchantLifecycleService merchantLifecycle,
                                      ShopLifecycleService shopLifecycle,
                                      ShopMembership membership) {
        this.merchantLifecycle = merchantLifecycle;
        this.shopLifecycle = shopLifecycle;
        this.membership = membership;
    }

    // ------------------------------------------------------------ merchants

    public record MerchantView(Long id, String legalName, String displayName, String contactPhone,
                               String contactEmail, MerchantStatus status, String statusReason,
                               Long ownerCustomerId, Boolean isDemo, Boolean active) {
        static MerchantView of(Merchant m) {
            return new MerchantView(m.getId(), m.getLegalName(), m.getDisplayName(),
                    m.getContactPhone(), m.getContactEmail(), m.getStatus(), m.getStatusReason(),
                    m.getOwnerCustomerId(), m.getIsDemo(), m.getActive());
        }
    }

    public record RegisterMerchantRequest(String legalName, String displayName, String contactPhone,
                                          String contactEmail, Long ownerCustomerId, Boolean demo) {}

    public record StatusChangeRequest(String status, String reason) {}

    @GetMapping("/merchants")
    public List<MerchantView> merchants() {
        return merchantLifecycle.all().stream().map(MerchantView::of).toList();
    }

    @GetMapping("/merchants/{id}")
    public MerchantView merchant(@PathVariable Long id) {
        return MerchantView.of(merchantLifecycle.byId(id));
    }

    @PostMapping("/merchants")
    public MerchantView register(@RequestBody RegisterMerchantRequest request) {
        return MerchantView.of(merchantLifecycle.register(
                request.legalName(), request.displayName(), request.contactPhone(),
                request.contactEmail(), request.ownerCustomerId(),
                Boolean.TRUE.equals(request.demo())));
    }

    @PutMapping("/merchants/{id}/status")
    public MerchantView moveMerchant(@PathVariable Long id, @RequestBody StatusChangeRequest request) {
        return MerchantView.of(merchantLifecycle.transition(
                id, parseMerchantStatus(request.status()), request.reason()));
    }

    // ---------------------------------------------------------------- shops

    public record ShopView(Long id, Long merchantId, String code, String displayName,
                           ShopStatus status, String statusReason, Boolean isDemo, Boolean active) {
        static ShopView of(Shop s) {
            return new ShopView(s.getId(), s.getMerchantId(), s.getCode(), s.getDisplayName(),
                    s.getStatus(), s.getStatusReason(), s.getIsDemo(), s.getActive());
        }
    }

    public record OpenShopRequest(Long merchantId, String code, String displayName,
                                  Double latitude, Double longitude,
                                  BigDecimal maxDeliveryRadiusKm, String timeZone) {}

    public record StaffRequest(Long customerId, Boolean asDefault) {}

    @GetMapping("/shops")
    public List<ShopView> shops(@RequestParam(required = false) Long merchantId) {
        List<Shop> found = merchantId == null
                ? shopLifecycle.all() : shopLifecycle.forMerchant(merchantId);
        return found.stream().map(ShopView::of).toList();
    }

    @PostMapping("/shops")
    public ShopView open(@RequestBody OpenShopRequest request) {
        return ShopView.of(shopLifecycle.open(request.merchantId(), request.code(),
                request.displayName(), request.latitude(), request.longitude(),
                request.maxDeliveryRadiusKm(), request.timeZone()));
    }

    @PutMapping("/shops/{id}/status")
    public ShopView moveShop(@PathVariable Long id, @RequestBody StatusChangeRequest request) {
        return ShopView.of(shopLifecycle.transitionAsPlatform(
                id, parseShopStatus(request.status()), request.reason()));
    }

    /**
     * Puts an account on a shop's staff list.
     *
     * THE ONLY WAY A STAFF ACCOUNT GETS A SHOP, and therefore the only way one
     * gets a tenant scope. Deliberately platform-level for now: letting a shop
     * add its own staff is a reasonable next step, but "who may work here" is
     * the hinge the whole isolation model turns on, and a shop that could add
     * an account could add one belonging to somebody else.
     */
    @PostMapping("/shops/{shopId}/staff")
    public void addStaff(@PathVariable Long shopId, @RequestBody StaffRequest request) {
        membership.grant(shopId, request.customerId(), Boolean.TRUE.equals(request.asDefault()));
    }

    @DeleteMapping("/shops/{shopId}/staff/{customerId}")
    public void removeStaff(@PathVariable Long shopId, @PathVariable Long customerId) {
        membership.revoke(shopId, customerId);
    }

    private static MerchantStatus parseMerchantStatus(String raw) {
        try {
            return MerchantStatus.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new com.gpstore.exception.BadRequestException("Unknown merchant status: " + raw);
        }
    }

    private static ShopStatus parseShopStatus(String raw) {
        try {
            return ShopStatus.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new com.gpstore.exception.BadRequestException("Unknown shop status: " + raw);
        }
    }
}
