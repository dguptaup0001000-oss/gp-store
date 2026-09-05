package com.gpstore.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Makes the existing shop into Shop #1, from the configuration it already runs on.
 *
 * THE EXISTING SHOP IS NOT BEING REPLACED (§104). It is the first shop already
 * running on the platform, and this is the row that says so. Its coordinates,
 * delivery radius, timezone and support numbers are read from the very
 * STORE_* properties the running system uses today, so the row describes the
 * shop as it actually is rather than as somebody typed it a second time.
 *
 * IDEMPOTENT AND NON-DESTRUCTIVE. It creates the merchant and shop only if
 * they are absent, and never overwrites a row somebody has since edited - the
 * shopkeeper's own name for their shop must not be reverted to an environment
 * variable on the next restart.
 *
 * NAMES ARE PLACEHOLDERS UNTIL THE OWNER GIVES REAL ONES. platform.first-shop.name
 * and platform.first-merchant.legal-name override them. The default is
 * deliberately obvious rather than plausible: a made-up legal name sitting in
 * a business record is worse than an obviously unset one.
 */
@Component
public class ShopBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopBootstrap.class);

    private final MerchantRepository merchants;
    private final ShopRepository shops;
    private final PlatformProperties platform;

    private final Double latitude;
    private final Double longitude;
    private final BigDecimal maxRadiusKm;
    private final String timeZone;
    private final String supportPhone;
    private final String supportEmail;
    private final String supportWhatsapp;

    public ShopBootstrap(MerchantRepository merchants,
                         ShopRepository shops,
                         PlatformProperties platform,
                         @Value("${store.latitude:0}") Double latitude,
                         @Value("${store.longitude:0}") Double longitude,
                         @Value("${store.max-delivery-radius-km:20}") BigDecimal maxRadiusKm,
                         @Value("${store.schedule.zone:Asia/Kolkata}") String timeZone,
                         @Value("${store.support-phone:}") String supportPhone,
                         @Value("${store.support-email:}") String supportEmail,
                         @Value("${store.support-whatsapp:}") String supportWhatsapp) {
        this.merchants = merchants;
        this.shops = shops;
        this.platform = platform;
        this.latitude = latitude;
        this.longitude = longitude;
        this.maxRadiusKm = maxRadiusKm;
        this.timeZone = timeZone;
        this.supportPhone = supportPhone;
        this.supportEmail = supportEmail;
        this.supportWhatsapp = supportWhatsapp;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        java.util.Optional<Shop> existing = shops.findByCode(platform.getFirstShopCode());

        if (existing.isPresent()) {
            completeMissingDetails(existing.get());
            return;
        }

        Merchant merchant = merchants.findAll().stream().findFirst().orElseGet(this::createFirstMerchant);
        Shop shop = createFirstShop(merchant.getId());

        log.info("Shop #1 created as '{}' (code {}) under merchant '{}'. Platform mode is {}.",
                shop.getDisplayName(), shop.getCode(), merchant.getLegalName(), platform.getMode());
    }

    /**
     * Fills in what the migration could not, and nothing else.
     *
     * THE MIGRATION CREATES SHOP #1 WITHOUT A LOCATION, because SQL cannot
     * read the environment and freezing today's coordinates into a migration
     * file would leave a copy nobody looks at again. So on a real deploy the
     * row arrives with latitude, radius and support numbers empty, and this
     * is what completes it - a shop with no location cannot be delivered
     * from, and an incomplete row that nothing repairs is worse than no row.
     *
     * ONLY WHERE THE VALUE IS ABSENT. Once the shopkeeper renames their shop
     * or corrects the pin the map put in the wrong lane, that is now the
     * truth and an environment variable must not revert it on the next
     * restart. Every field below is written only when it is currently null.
     */
    private void completeMissingDetails(Shop shop) {
        boolean changed = false;

        if (shop.getLatitude() == null && latitude != null) {
            shop.setLatitude(latitude);
            changed = true;
        }
        if (shop.getLongitude() == null && longitude != null) {
            shop.setLongitude(longitude);
            changed = true;
        }
        if (shop.getMaxDeliveryRadiusKm() == null && maxRadiusKm != null) {
            shop.setMaxDeliveryRadiusKm(maxRadiusKm);
            changed = true;
        }
        if (shop.getTimeZone() == null && blankToNull(timeZone) != null) {
            shop.setTimeZone(timeZone);
            changed = true;
        }
        if (shop.getSupportPhone() == null && blankToNull(supportPhone) != null) {
            shop.setSupportPhone(supportPhone);
            changed = true;
        }
        if (shop.getSupportEmail() == null && blankToNull(supportEmail) != null) {
            shop.setSupportEmail(supportEmail);
            changed = true;
        }
        if (shop.getSupportWhatsapp() == null && blankToNull(supportWhatsapp) != null) {
            shop.setSupportWhatsapp(supportWhatsapp);
            changed = true;
        }

        if (changed) {
            shops.save(shop);
            log.info("Shop #1 ('{}') completed from STORE_* configuration.", shop.getDisplayName());
        }
    }

    private Merchant createFirstMerchant() {
        Merchant merchant = new Merchant();
        merchant.setLegalName(blankTo(platform.getFirstMerchantName(),
                "UNNAMED MERCHANT - set platform.first-merchant.legal-name"));
        merchant.setDisplayName(blankTo(platform.getFirstShopName(), "GP Store"));
        merchant.setContactPhone(blankToNull(supportPhone));
        merchant.setContactEmail(blankToNull(supportEmail));
        // The shop that has been trading all along is, by definition, approved.
        merchant.setStatus(MerchantStatus.ACTIVE);
        merchant.setStatusReason("The original GP-STORE shop, trading before the marketplace existed.");
        merchant.setIsDemo(Boolean.FALSE);
        merchant.setActive(Boolean.TRUE);
        return merchants.save(merchant);
    }

    private Shop createFirstShop(Long merchantId) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode(platform.getFirstShopCode());
        shop.setDisplayName(blankTo(platform.getFirstShopName(), "GP Store"));
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(latitude);
        shop.setLongitude(longitude);
        shop.setMaxDeliveryRadiusKm(maxRadiusKm);
        shop.setTimeZone(timeZone);
        shop.setSupportPhone(blankToNull(supportPhone));
        shop.setSupportEmail(blankToNull(supportEmail));
        shop.setSupportWhatsapp(blankToNull(supportWhatsapp));
        shop.setIsDemo(Boolean.FALSE);
        shop.setActive(Boolean.TRUE);
        return shops.save(shop);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
