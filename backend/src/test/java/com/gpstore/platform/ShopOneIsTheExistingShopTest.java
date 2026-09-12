package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shop that has been trading all along becomes Shop #1.
 *
 * WE ARE NOT REPLACING IT. The existing single-shop system is the first shop
 * already running on the platform, and Slice 0's job is to say so in a row
 * without changing a single thing about how it behaves.
 *
 * The row is built from the STORE_* configuration the application actually
 * runs on rather than from values typed a second time into a migration,
 * because two copies of the shop's coordinates is two chances for them to
 * disagree - and the one in the file nobody looks at again would be the wrong
 * one.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Shop #1 is the shop that was already there")
class ShopOneIsTheExistingShopTest {

    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private ShopBootstrap bootstrap;

    @Value("${store.latitude}") double configuredLatitude;
    @Value("${store.longitude}") double configuredLongitude;

    @Test
    @DisplayName("it exists, it is trading, and it belongs to a merchant")
    void shopOneExists() {
        Shop shop = shops.findByCode(platform.getFirstShopCode()).orElseThrow(
                () -> new AssertionError("Shop #1 was never created"));

        assertEquals(ShopStatus.ACTIVE, shop.getStatus(),
                "the shop has been taking orders for months; it is not a draft");
        assertNotNull(shop.getMerchantId(), "a shop with no business behind it is not a shop");

        Merchant merchant = merchants.findById(shop.getMerchantId()).orElseThrow();
        assertEquals(MerchantStatus.ACTIVE, merchant.getStatus(),
                "a shop that is already trading cannot be pending review");
    }

    @Test
    @DisplayName("neither the merchant nor the shop is marked demo")
    void theRealShopIsNotDemo() {
        // Demo merchants exist to be demonstrated, never to be counted as
        // traction. The real shop must never be confused for one.
        Shop shop = shops.findByCode(platform.getFirstShopCode()).orElseThrow();
        assertFalse(shop.getIsDemo(), "Shop #1 is a real business");
        assertFalse(merchants.findById(shop.getMerchantId()).orElseThrow().getIsDemo());
    }

    @Test
    @DisplayName("it carries the coordinates and radius the app actually runs on")
    void geographyComesFromTheRunningConfiguration() {
        Shop shop = shops.findByCode(platform.getFirstShopCode()).orElseThrow();

        assertNotNull(shop.getLatitude(), "a shop with no location cannot be delivered from");
        assertNotNull(shop.getLongitude());
        assertEquals(configuredLatitude, shop.getLatitude(), 0.000001,
                "the row must describe where the shop IS, per store.latitude");
        assertEquals(configuredLongitude, shop.getLongitude(), 0.000001);

        assertNotNull(shop.getMaxDeliveryRadiusKm());
        assertTrue(shop.getMaxDeliveryRadiusKm().compareTo(BigDecimal.ZERO) > 0,
                "a radius of zero would make the shop serve nobody");
        assertNotNull(shop.getTimeZone(), "opening hours mean nothing without a zone");
    }

    @Test
    @DisplayName("running the bootstrap again does not create a second shop")
    void bootstrapIsIdempotent() {
        // Every restart runs this. A bootstrap that created a shop each time
        // would have the marketplace full of duplicates of the same kirana
        // shop by the end of a week of deploys.
        long shopsBefore = shops.count();
        long merchantsBefore = merchants.count();

        bootstrap.run(null);
        bootstrap.run(null);

        assertEquals(shopsBefore, shops.count(), "a restart must not create another shop");
        assertEquals(merchantsBefore, merchants.count());
    }
}
