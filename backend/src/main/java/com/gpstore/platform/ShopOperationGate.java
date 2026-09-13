package com.gpstore.platform;

import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A shop under enforcement is READ-ONLY, not invisible.
 *
 * WHAT THIS CLOSES. Suspending a merchant already cascades to its shops -
 * MerchantLifecycleService moves every ACTIVE or PAUSED shop to SUSPENDED -
 * and ShopTradingGate stops customers ordering from it. What nothing stopped
 * was the merchant themselves: {@link ShopMembership#isOperable} refuses only
 * REMOVED and REJECTED merchants, so the owner of a SUSPENDED business kept
 * full write access to their own back office. They could change prices, edit
 * the catalogue, move orders and pay themselves out, while the platform
 * believed it had stopped them.
 *
 * WHY READ-ONLY RATHER THAN LOCKED OUT, and this is the part that took
 * reading the code to get right:
 *
 *   THE APPEAL LIVES BEHIND THE SAME GATE. A merchant disputes an enforcement
 *   action at POST /api/shop/governance/actions/{id}/appeal, and reads why
 *   they were suspended at GET /api/shop/governance. Refusing the scope
 *   outright would suspend a business and, in the same stroke, remove its
 *   only way to ask why - which is not enforcement, it is a dead end.
 *
 *   ORDERS ALREADY PLACED STILL HAVE TO ARRIVE. A rider resolves to the shop
 *   on their roster row, so a blanket write ban would strand paid orders
 *   mid-delivery and leave customers with neither goods nor a refund. The
 *   suspension is against the merchant, not against the shopper waiting at
 *   the door.
 *
 *   THE PLATFORM MUST STILL BE ABLE TO ACT. Super Admin manages suspended
 *   shops - that is what suspension is for - so PLATFORM_ADMIN is exempt.
 *
 * READS ARE UNTOUCHED. A suspended merchant can see their orders, their
 * takings and their catalogue. Hiding the record of a business from the
 * person who owns it is not a platform decision this class is willing to
 * make, and §4 of the specification is explicit that suspension must not
 * quietly become deletion.
 */
@Component
public class ShopOperationGate {

    private final ShopRepository shops;
    private final MerchantRepository merchants;
    private final CurrentUser currentUser;

    public ShopOperationGate(ShopRepository shops, MerchantRepository merchants,
                             CurrentUser currentUser) {
        this.shops = shops;
        this.merchants = merchants;
        this.currentUser = currentUser;
    }

    /**
     * Whether the business behind this shop may still be OPERATED by its own
     * people.
     *
     * Deliberately separate from {@link ShopMembership#isOperable}, which
     * answers a different question - "does this scope resolve at all" - and
     * must keep saying yes for a suspended shop so its owner can sign in and
     * appeal.
     */
    public boolean mayOperate(Long shopId) {
        if (shopId == null) {
            return true;
        }
        Optional<Shop> shop = shops.findById(shopId);
        if (shop.isEmpty()) {
            return false;
        }
        ShopStatus shopStatus = shop.get().getStatus();
        if (shopStatus == ShopStatus.SUSPENDED || shopStatus == ShopStatus.CLOSED) {
            return false;
        }
        Long merchantId = shop.get().getMerchantId();
        if (merchantId == null) {
            return false;
        }
        return merchants.findById(merchantId)
                .map(m -> switch (m.getStatus()) {
                    // PAUSED is a business state, not a punishment (§4), and it
                    // stops the shop trading rather than stopping the
                    // shopkeeper working. A merchant who is paused is
                    // typically getting ready to come back, and taking their
                    // catalogue away from them while they do would make the
                    // pause harder to end than it was to start.
                    case ACTIVE, APPROVED, PAUSED -> true;
                    default -> false;
                })
                .orElse(false);
    }

    /** True when this caller is the platform, which manages suspended shops. */
    public boolean isPlatformActor() {
        return currentUser.has(AdminPermission.PLATFORM_ADMIN);
    }
}
