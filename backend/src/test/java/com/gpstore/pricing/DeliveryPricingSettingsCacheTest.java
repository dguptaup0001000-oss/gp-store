package com.gpstore.pricing;

import com.gpstore.entity.DeliveryPricingSettings;
import com.gpstore.repository.DeliveryPricingSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The pricing settings are read once and remembered, not read per quote.
 *
 * MY OWN REGRESSION, found while auditing the load test. Every checkout
 * preview and every placed order calls DeliveryPricingService.settings(), and
 * preview runs on every cart change - so a table with exactly one row, edited
 * a few times a year, was costing a database round trip on the busiest path in
 * the application, at the precise moment its ten connections are scarcest.
 *
 * THIS IS A SAFE THING TO CACHE and the distinction matters, because most of
 * what surrounds it is not. It is shop configuration: not user-specific, not
 * inventory, not payment state, and nothing that goes stale in a way that can
 * cost money or oversell stock. The one risk is an admin edit not taking
 * effect, which is what the eviction test below is for.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Delivery pricing settings are cached, and an edit is still immediate")
class DeliveryPricingSettingsCacheTest {

    private static final String CACHE = "deliveryPricingSettings";

    @Autowired private DeliveryPricingService pricingService;
    @Autowired private CacheManager cacheManager;
    @MockitoSpyBean private DeliveryPricingSettingsRepository repository;

    @BeforeEach
    void startCold() {
        var cache = cacheManager.getCache(CACHE);
        assertNotNull(cache, "No '" + CACHE + "' cache - the @Cacheable is not wired to anything.");
        cache.clear();
        clearInvocations(repository);
    }

    @Test
    @DisplayName("repeated reads cost one database round trip, not one each")
    void settingsAreReadOnceAndReused() {
        pricingService.settings();
        verify(repository, atLeastOnce()).findById(DeliveryPricingSettings.SINGLETON_ID);
        clearInvocations(repository);

        for (int i = 0; i < 25; i++) {
            assertNotNull(pricingService.settings());
        }

        verify(repository, times(0)).findById(DeliveryPricingSettings.SINGLETON_ID);
    }

    @Test
    @DisplayName("a checkout quote uses the cached settings, not a self-invocation miss")
    void quoteHitsTheSettingsCache() {
        // quote() used to call this.settings() on the raw instance, which
        // skips the Spring cache proxy. Preview and placeOrder go through
        // quote, so a cache that only works for settings() was a miss on
        // the only path that matters.
        pricingService.quoteForCart(java.util.List.of(), null);
        verify(repository, atLeastOnce()).findById(DeliveryPricingSettings.SINGLETON_ID);
        clearInvocations(repository);

        for (int i = 0; i < 10; i++) {
            assertNotNull(pricingService.quoteForCart(java.util.List.of(), null));
        }

        verify(repository, times(0)).findById(DeliveryPricingSettings.SINGLETON_ID);
    }

    @Test
    @DisplayName("saving new prices makes them visible on the very next quote")
    void anAdminEditIsNotHiddenByTheCache() {
        BigDecimal originalTier1 = pricingService.settings().getDistanceTier1Charge();
        try {
            DeliveryPricingSettings edited = pricingService.settings();
            // A value nothing else in the suite uses, so a failure here is
            // unambiguous rather than a collision with another test's fixture.
            edited.setDistanceTier1Charge(new BigDecimal("7.00"));
            pricingService.save(edited, "cache eviction test");

            assertEquals(0, new BigDecimal("7.00").compareTo(
                            pricingService.settings().getDistanceTier1Charge()),
                    "The cache served the old price after an admin saved a new one. A cache that hides "
                            + "an edit is worse than no cache: the shop believes it changed a price and "
                            + "it did not.");
        } finally {
            DeliveryPricingSettings restore = pricingService.settings();
            restore.setDistanceTier1Charge(originalTier1);
            pricingService.save(restore, "cache eviction test cleanup");
        }
    }

    @Test
    @DisplayName("the cached type survives the serializer the cache actually uses")
    void theEntitySerialises() throws Exception {
        // Spring's default RedisCacheManager uses JDK serialization. A type
        // that is not Serializable does not fail at startup, and does not fail
        // in any test that misses the cache - it throws NotSerializableException
        // from inside the cache WRITE, on the first request that populates it,
        // in production. Which is to say: exactly where nobody is looking.
        DeliveryPricingSettings settings = pricingService.settings();
        assertInstanceOf(java.io.Serializable.class, settings);

        try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
            assertDoesNotThrow(() -> out.writeObject(settings),
                    "The settings entity cannot be JDK-serialised, so every attempt to cache it fails.");
        }
    }

    @Test
    @DisplayName("a cache miss still produces a usable price rather than an outage")
    void aFailedReadDoesNotTakeCheckoutDown() {
        // The settings read sits inside checkout. Its fallback returns the
        // built-in V1 defaults rather than throwing, because a configuration
        // table being unreadable must not stop the shop selling. Asserted here
        // so the fallback is not quietly removed as dead code.
        cacheManager.getCache(CACHE).clear();
        org.mockito.Mockito.doThrow(new RuntimeException("simulated settings read failure"))
                .when(repository).findById(any());

        DeliveryPricingSettings settings = assertDoesNotThrow(() -> pricingService.settings());
        assertNotNull(settings.getDistanceTier1Charge());
        assertTrue(settings.getDistanceTier1Charge().signum() > 0,
                "The fallback must price delivery, not give it away.");

        org.mockito.Mockito.reset(repository);
        cacheManager.getCache(CACHE).clear();
    }
}
