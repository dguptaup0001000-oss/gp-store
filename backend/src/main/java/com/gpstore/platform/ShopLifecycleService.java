package com.gpstore.platform;

import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Opening, pausing, suspending and closing one storefront.
 *
 * A SHOP'S STATUS IS NOT ITS MERCHANT'S. A business running three kiranas may
 * want one closed for a month without touching the other two, and the platform
 * may need to stop one shop without suspending a business that is otherwise
 * fine. That is why there are two lifecycles rather than one.
 *
 * WHO MAY MAKE WHICH MOVE IS PART OF THE MODEL, not just of the route.
 * Pausing and reopening are the shopkeeper's own business; suspending,
 * un-suspending and closing are the platform's. A shop that could clear its
 * own suspension would make suspension meaningless, so
 * {@link #transitionAsMerchant} refuses those moves whatever route it is
 * called from.
 */
@Service
public class ShopLifecycleService {

    private final ShopRepository shops;
    private final MerchantRepository merchants;
    private final ShopMembership membership;
    private final AuditLogService auditLog;
    private final com.gpstore.repository.StoreOperationsSettingsRepository storeSettings;
    private final com.gpstore.repository.DeliveryPricingSettingsRepository pricingSettings;

    public ShopLifecycleService(ShopRepository shops, MerchantRepository merchants,
                                ShopMembership membership, AuditLogService auditLog,
                                com.gpstore.repository.StoreOperationsSettingsRepository storeSettings,
                                com.gpstore.repository.DeliveryPricingSettingsRepository pricingSettings) {
        this.shops = shops;
        this.merchants = merchants;
        this.membership = membership;
        this.auditLog = auditLog;
        this.storeSettings = storeSettings;
        this.pricingSettings = pricingSettings;
    }

    @Transactional(readOnly = true)
    public List<Shop> all() {
        return shops.findAll();
    }

    @Transactional(readOnly = true)
    public List<Shop> forMerchant(Long merchantId) {
        return shops.findByMerchantId(merchantId);
    }

    @Transactional(readOnly = true)
    public Shop byId(Long id) {
        return shops.findById(id).orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
    }

    /**
     * Opens a storefront under a merchant. It starts as a DRAFT.
     *
     * REFUSES A MERCHANT THAT HAS NOT BEEN APPROVED. A shop under a business
     * nobody checked is a shop taking real customers' money on the strength of
     * an application form.
     *
     * The merchant's owner becomes the shop's first staff member, so a brand
     * new shop is not one nobody can sign in to.
     */
    @Transactional
    public Shop open(Long merchantId, String code, String displayName,
                     Double latitude, Double longitude, BigDecimal maxRadiusKm, String timeZone) {
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found"));

        if (merchant.getStatus() != MerchantStatus.APPROVED
                && merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new ConflictException(
                    "A shop can only be opened under an approved merchant. This one is "
                            + merchant.getStatus() + ".");
        }
        if (code == null || code.isBlank()) {
            throw new BadRequestException("A shop needs a code.");
        }
        if (shops.findByCode(code.trim()).isPresent()) {
            throw new ConflictException("A shop with that code already exists.");
        }

        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(code.trim());
        shop.setDisplayName(displayName == null || displayName.isBlank()
                ? merchant.getDisplayName() : displayName.trim());
        shop.setStatus(ShopStatus.DRAFT);
        shop.setLatitude(latitude);
        shop.setLongitude(longitude);
        shop.setMaxDeliveryRadiusKm(maxRadiusKm);
        shop.setTimeZone(timeZone);
        shop.setIsDemo(merchant.getIsDemo());
        shop.setActive(Boolean.TRUE);

        Shop saved = shops.save(shop);
        auditLog.log("SHOP_OPENED", "Shop", saved.getId(),
                "merchant=" + merchantId + ", code=" + saved.getCode() + ", status=DRAFT");

        // A NEW SHOP IS COMPLETE FROM THE START. Its operating settings and
        // its delivery pricing are per-shop rows (V49), and a shop without
        // them is one whose first checkout tries to create them - which, on a
        // read-only preview, is a 500 rather than a missing row. Created here,
        // with the defaults, inside the shop's own scope so they are stamped
        // to it.
        TenantContext.runWithin(TenantScope.ofShop(saved.getId()), () -> {
            if (storeSettings.findByShopId(saved.getId()).isEmpty()) {
                storeSettings.save(new com.gpstore.entity.StoreOperationsSettings());
            }
            if (pricingSettings.findByShopId(saved.getId()).isEmpty()) {
                com.gpstore.entity.DeliveryPricingSettings pricing =
                        new com.gpstore.entity.DeliveryPricingSettings();
                pricing.normalise();
                pricing.setUpdatedBy("created with the shop");
                pricingSettings.save(pricing);
            }
            return null;
        });

        if (merchant.getOwnerCustomerId() != null) {
            membership.grant(saved.getId(), merchant.getOwnerCustomerId(), true);
        }
        return saved;
    }

    /** A platform-level move: any transition the table allows. */
    @Transactional
    public Shop transitionAsPlatform(Long shopId, ShopStatus next, String reason) {
        return move(byId(shopId), next, reason, "PLATFORM");
    }

    /**
     * A shopkeeper's own move, which is a strictly smaller set.
     *
     * Pausing for a holiday and reopening afterwards - nothing else. Closing a
     * shop for good and lifting a platform suspension are decisions with
     * consequences for customers with orders in flight, and they do not belong
     * to the person the suspension is about.
     */
    @Transactional
    public Shop transitionAsMerchant(Long shopId, ShopStatus next, String reason) {
        Shop shop = byId(shopId);
        if (!shop.getStatus().isMerchantChoice(next)) {
            throw new ConflictException(
                    "A shop may pause and reopen itself. Going from " + shop.getStatus()
                            + " to " + next + " is a platform decision.");
        }
        return move(shop, next, reason, "MERCHANT");
    }

    private Shop move(Shop shop, ShopStatus next, String reason, String actorKind) {
        ShopStatus current = shop.getStatus();
        if (current == next) {
            return shop;
        }
        if (!current.canMoveTo(next)) {
            throw new ConflictException(
                    "A shop cannot go from " + current + " to " + next
                            + ". Allowed from here: " + current.allowedNext());
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required for every shop status change.");
        }

        shop.setStatus(next);
        shop.setStatusReason(reason.trim());
        shop.setUpdatedAt(LocalDateTime.now());
        if (next == ShopStatus.CLOSED) {
            shop.setActive(Boolean.FALSE);
        }

        Shop saved = shops.save(shop);
        auditLog.log("SHOP_STATUS_CHANGED", "Shop", saved.getId(),
                actorKind + ": " + current + " -> " + next + ": " + reason.trim());
        return saved;
    }
}
