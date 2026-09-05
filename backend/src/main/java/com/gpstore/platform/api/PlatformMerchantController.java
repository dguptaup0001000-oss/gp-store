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
    private final com.gpstore.money.ShopEarnings earnings;

    public PlatformMerchantController(MerchantLifecycleService merchantLifecycle,
                                      ShopLifecycleService shopLifecycle,
                                      ShopMembership membership,
                                      com.gpstore.money.ShopEarnings earnings) {
        this.merchantLifecycle = merchantLifecycle;
        this.shopLifecycle = shopLifecycle;
        this.membership = membership;
        this.earnings = earnings;
    }

    // ------------------------------------------------------------- the market

    /**
     * @param shops     one line per shop that traded in the window, best first
     * @param totals    the marketplace added up
     * @param shopCount how many shops exist, whether or not they traded
     */
    public record MarketOverview(int periodDays,
                                 List<com.gpstore.money.ShopEarnings.ShopLine> shops,
                                 Totals totals, long shopCount, long merchantCount) {}

    public record Totals(long orderCount, long cancelledCount, BigDecimal grossSales,
                         BigDecimal refunds, BigDecimal netSales, int tradingShops) {}

    /**
     * How the marketplace is doing, shop by shop.
     *
     * THE ONE PLACE FIGURES FROM DIFFERENT MERCHANTS SIT SIDE BY SIDE, and
     * that is what a platform administrator is for. Two things keep it from
     * being the leak the rest of this transformation exists to prevent:
     *
     *   PERM_PLATFORM_ADMIN, which no shop ADMIN holds - RolePermissions
     *   builds every shop's permission set by SUBTRACTING it, so a shopkeeper
     *   cannot reach this route however senior they are inside their own shop;
     *
     *   and the query is scoped by the caller's own tenant scope, not by the
     *   route. If this gate were ever removed, a shopkeeper reaching the route
     *   would resolve to their own shop and read one line: their own. It fails
     *   closed rather than open, which is the difference between a bug and a
     *   breach.
     *
     * KEYED BY SHOP RATHER THAN POOLED. A single marketplace total would hide
     * exactly what an operator needs to see - one shop cancelling everything,
     * another taking no orders at all.
     */
    @GetMapping("/overview")
    public MarketOverview overview(@RequestParam(defaultValue = "30") int days) {
        List<com.gpstore.money.ShopEarnings.ShopLine> lines = earnings.byShop(days);

        long orderCount = 0;
        long cancelled = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        for (com.gpstore.money.ShopEarnings.ShopLine line : lines) {
            orderCount += line.orderCount();
            cancelled += line.cancelledCount();
            gross = gross.add(line.grossSales());
            refunded = refunded.add(line.refunds());
        }

        return new MarketOverview(days, lines,
                new Totals(orderCount, cancelled, gross, refunded, gross.subtract(refunded),
                        lines.size()),
                shopLifecycle.all().size(),
                merchantLifecycle.all().size());
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
