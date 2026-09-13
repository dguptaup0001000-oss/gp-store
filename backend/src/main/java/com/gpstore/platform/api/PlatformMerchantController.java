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
    private final com.gpstore.platform.PlatformStaffService staffService;
    private final com.gpstore.platform.PlatformOnboardingService onboardingService;

    public PlatformMerchantController(MerchantLifecycleService merchantLifecycle,
                                      ShopLifecycleService shopLifecycle,
                                      ShopMembership membership,
                                      com.gpstore.money.ShopEarnings earnings,
                                      com.gpstore.platform.PlatformStaffService staffService,
                                      com.gpstore.platform.PlatformOnboardingService onboardingService) {
        this.merchantLifecycle = merchantLifecycle;
        this.shopLifecycle = shopLifecycle;
        this.membership = membership;
        this.earnings = earnings;
        this.staffService = staffService;
        this.onboardingService = onboardingService;
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

    public record MerchantView(Long id,
                               /** M-000001. For reading aloud, never for deciding (§3). */
                               String merchantRef,
                               String legalName, String displayName, String contactPhone,
                               String contactEmail, MerchantStatus status, String statusReason,
                               Long ownerCustomerId, Boolean isDemo, Boolean active,
                               java.time.LocalDateTime createdAt,
                               java.time.LocalDateTime updatedAt) {
        static MerchantView of(Merchant m) {
            return new MerchantView(m.getId(), com.gpstore.platform.PublicIds.merchant(m.getId()),
                    m.getLegalName(), m.getDisplayName(),
                    m.getContactPhone(), m.getContactEmail(), m.getStatus(), m.getStatusReason(),
                    m.getOwnerCustomerId(), m.getIsDemo(), m.getActive(),
                    m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    public record RegisterMerchantRequest(String legalName, String displayName, String contactPhone,
                                          String contactEmail, Long ownerCustomerId, Boolean demo) {}

    public record StatusChangeRequest(String status, String reason) {}

    /**
     * One merchant and every shop under it (§53, §54).
     *
     * ONE CALL, BECAUSE THE QUESTION IS ONE QUESTION. "Deepak Enterprises,
     * three shops, one of them paused" is what the platform owner is actually
     * looking at, and assembling it from two endpoints on the client means a
     * screen that can show a merchant beside somebody else's shops for as long
     * as the second request is in flight.
     *
     * THE SHOPS ARE READ BY MERCHANT ID FROM THE DATABASE, not filtered from a
     * list the caller sent. There is nothing here a client could point at
     * another merchant's storefronts.
     */
    @GetMapping("/merchants/{merchantId}/detail")
    public MerchantDetail merchantDetail(@PathVariable Long merchantId) {
        Merchant merchant = merchantLifecycle.byId(merchantId);
        List<ShopView> theirShops = shopLifecycle.forMerchant(merchantId).stream()
                .map(ShopView::of)
                .toList();
        return new MerchantDetail(MerchantView.of(merchant), theirShops, theirShops.size());
    }

    public record MerchantDetail(MerchantView merchant, List<ShopView> shops, int shopCount) {}

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

    public record ShopView(Long id,
                           /** S-000001. Beside shops.code, not instead of it (§43). */
                           String shopRef,
                           Long merchantId,
                           /** M-000001, so a shop names its merchant without a second call. */
                           String merchantRef,
                           String code, String displayName,
                           ShopStatus status, String statusReason, Boolean isDemo, Boolean active) {
        static ShopView of(Shop s) {
            return new ShopView(s.getId(), com.gpstore.platform.PublicIds.shop(s.getId()),
                    s.getMerchantId(), com.gpstore.platform.PublicIds.merchant(s.getMerchantId()),
                    s.getCode(), s.getDisplayName(),
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
     * How far GP-STORE has checked who a shop is (§10).
     *
     * <p>THE ONLY ROUTE THAT CAN SET THIS, AND IT IS THE PLATFORM'S. A
     * merchant who could verify their own shop has been verified by nobody,
     * and the badge would mean exactly nothing to the customer reading it -
     * so ShopSelfServiceController has no route that touches it, which is a
     * fact ShopVerificationIsEarnedTest asserts rather than a convention.
     *
     * <p>TRUSTED IS NOT SETTABLE HERE EITHER, and not because it was left out:
     * it is not a value anywhere. It is computed from the shop's own trading
     * record (ShopReliability), so there is nothing to grant, nothing to
     * backfill, and nothing to sell.
     */
    @PutMapping("/shops/{id}/verification")
    public ShopView setVerification(@PathVariable Long id,
                                    @RequestBody VerificationRequest request) {
        com.gpstore.platform.ShopVerificationLevel level;
        try {
            level = com.gpstore.platform.ShopVerificationLevel.valueOf(
                    String.valueOf(request.level()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new com.gpstore.exception.BadRequestException(
                    "Unknown verification level: " + request.level()
                            + ". TRUSTED is earned from the shop's trading record, not granted.");
        }
        return ShopView.of(shopLifecycle.verify(id, level, request.note()));
    }

    public record VerificationRequest(String level, String note) {}

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
        // ASKED FOR, SO DONE. "Add them and make this their default" is an
        // instruction from the platform console, not a hint - see
        // ShopMembership.grantAndMakeDefault. Without the split this line
        // answered 200 and changed nothing for anybody who already had a
        // home shop, which is every merchant opening their second one.
        if (Boolean.TRUE.equals(request.asDefault())) {
            membership.grantAndMakeDefault(shopId, request.customerId());
        } else {
            membership.grant(shopId, request.customerId(), false);
        }
    }

    @DeleteMapping("/shops/{shopId}/staff/{customerId}")
    public void removeStaff(@PathVariable Long shopId, @PathVariable Long customerId) {
        membership.revoke(shopId, customerId);
    }

    // --------------------------------------------------- one-screen onboard

    /**
     * Everything the platform owner can know about a new merchant.
     *
     * SIX FIELDS, AND FIVE OF THEM ARE UNAVOIDABLE. The business needs a
     * name; the owner needs a name and an email, because the email IS their
     * login; and the shop needs a location and a delivery radius or the
     * marketplace offers it to nobody. shopCode is the one that may be left
     * out - it is derived from the business name - and demo defaults to a
     * real merchant.
     *
     * Everything else the long form asks for (trading name, contact phone,
     * contact email, time zone) either has a sensible default or can be
     * edited afterwards, and asking for it up front is what made opening a
     * shop feel like paperwork.
     */
    public record OnboardMerchantRequest(String businessName,
                                         String ownerName,
                                         String ownerEmail,
                                         String ownerPhone,
                                         String shopCode,
                                         Double latitude,
                                         Double longitude,
                                         java.math.BigDecimal maxDeliveryRadiusKm,
                                         String timeZone,
                                         Boolean demo) {}

    /**
     * Opens a merchant's login, registers the business, walks it to APPROVED
     * and opens its shop - in one transaction.
     *
     * The one-time password is in this response and nowhere else, exactly as
     * for POST /staff.
     *
     * IT STOPS SHORT OF TRADING, deliberately. See
     * PlatformOnboardingService: the shop it opens has empty shelves, and a
     * findable shop with nothing to sell is worse than one that is not
     * findable yet.
     */
    @PostMapping("/onboard")
    public PlatformOnboardingService.OnboardedMerchant onboard(
            @RequestBody OnboardMerchantRequest request) {
        return onboardingService.onboard(
                request.businessName(),
                request.ownerName(),
                request.ownerEmail(),
                request.ownerPhone(),
                request.shopCode(),
                request.latitude(),
                request.longitude(),
                request.maxDeliveryRadiusKm(),
                request.timeZone(),
                Boolean.TRUE.equals(request.demo()));
    }

    // -------------------------------------------------------------- staff

    public record OpenStaffRequest(String fullName, String email, String mobileNumber,
                                   String role) {}

    /**
     * The one-time password is in the RESPONSE BODY and nowhere else.
     *
     * Not in a log line, not in a column, not in a second table. If the
     * owner loses it before handing it over, reset makes a new one - there
     * is no route that returns this one again, which is the property that
     * makes a platform-opened login safe to give somebody.
     */
    @PostMapping("/staff")
    public PlatformStaffService.OpenedAccount openStaffAccount(
            @RequestBody OpenStaffRequest request) {
        return staffService.openAccount(request.fullName(), request.email(),
                request.mobileNumber(), parseStaffRole(request.role()));
    }

    /**
     * Issues a new one-time password and kills every existing session.
     *
     * POST rather than PUT: it is not idempotent - each call mints a
     * different credential and invalidates the last one.
     */
    @PostMapping("/staff/{customerId}/reset-password")
    public PlatformStaffService.OpenedAccount resetStaffPassword(@PathVariable Long customerId) {
        return staffService.resetPassword(customerId);
    }

    /**
     * A new activation code, and the old one stops working immediately (§30).
     *
     * WHY THE PLATFORM CAN REPLACE IT BUT NOBODY CAN READ IT. The code is
     * stored as a fingerprint, so there is no route that could return the
     * existing one even if somebody wanted to write it. A merchant who lost
     * theirs before claiming the account is given a new one; the old one dies
     * in the same instant, because two live codes would mean a leaked one
     * stays usable after the merchant was told it had been replaced.
     *
     * THE REASON IS RECORDED AND THE SECRET IS NOT. A credential being
     * replaced is exactly the kind of act that has to be accountable later.
     * An audit log carrying the secret would just be a second place to steal
     * it from.
     */
    @PostMapping("/staff/{customerId}/reissue-activation-code")
    public PlatformStaffService.OpenedAccount reissueActivationCode(
            @PathVariable Long customerId,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return staffService.reissueActivationCode(customerId, reason);
    }

    private static com.gpstore.entity.Role parseStaffRole(String raw) {
        try {
            return com.gpstore.entity.Role.valueOf(
                    String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new com.gpstore.exception.BadRequestException("Unknown role: " + raw);
        }
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
