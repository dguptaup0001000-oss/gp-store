package com.gpstore.platform;

import com.gpstore.exception.ConflictException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether the shop this request is for is in a state to take an order at all.
 *
 * A DIFFERENT QUESTION FROM "ARE YOU OPEN RIGHT NOW". StoreOperationsSettings
 * answers that one - the shopkeeper's own switch, the delivery hours, the
 * closure message. This one asks whether the storefront exists on the
 * marketplace: a shop the platform suspended, one closed for good, or one
 * whose merchant has been removed must stop taking money whatever its own
 * switch says.
 *
 * IT IS CHECKED AT THE POINT OF SALE, not on every request. A suspended
 * shopkeeper can still sign in and read their own shop - being suspended and
 * being unable to find out why are different punishments - and the platform
 * needs them able to see the reason. What they cannot do is trade.
 */
@Component
public class ShopTradingGate {

    private final ShopRepository shops;
    private final MerchantRepository merchants;

    public ShopTradingGate(ShopRepository shops, MerchantRepository merchants) {
        this.shops = shops;
        this.merchants = merchants;
    }

    /**
     * Refuses if the shop in scope cannot trade.
     *
     * Silent when there is no shop scope: platform-wide work and background
     * jobs are not placing orders on anybody's behalf.
     */
    @Transactional(readOnly = true)
    public void requireCanAcceptOrders() {
        TenantScope scope = TenantContext.current();
        if (scope == null || scope.isPlatform()) {
            return;
        }
        Shop shop = shops.findById(scope.requireShopId()).orElse(null);
        if (shop == null) {
            throw new ConflictException("This shop is no longer available.");
        }
        if (!shop.getStatus().canAcceptOrders() || !Boolean.TRUE.equals(shop.getActive())) {
            throw new ConflictException(reasonFor(shop));
        }
        Long merchantId = shop.getMerchantId();
        Merchant merchant = merchantId == null ? null : merchants.findById(merchantId).orElse(null);
        if (merchant == null || !merchant.getStatus().canTrade()) {
            throw new ConflictException("This shop is not currently taking orders.");
        }
    }

    /**
     * What the customer is told.
     *
     * The shop's own words when it paused itself, because "Back on the 12th"
     * is a different message from a generic one - and NEVER the platform's
     * reason for a suspension, which is between the platform and the merchant
     * and is nobody else's business.
     */
    private static String reasonFor(Shop shop) {
        if (shop.getStatus() == ShopStatus.PAUSED && shop.getStatusReason() != null
                && !shop.getStatusReason().isBlank()) {
            return shop.getStatusReason();
        }
        return "This shop is not currently taking orders.";
    }
}
