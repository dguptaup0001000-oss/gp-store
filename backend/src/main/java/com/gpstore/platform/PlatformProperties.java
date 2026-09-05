package com.gpstore.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment-wide platform settings.
 *
 * Defaults to SINGLE_SHOP so that a deployment which sets nothing behaves
 * exactly as it does today. Turning the marketplace on has to be a deliberate
 * act, not something a missing environment variable can do by accident.
 */
@Component
public class PlatformProperties {

    private final PlatformMode mode;
    private final String firstShopCode;
    private final String firstShopName;
    private final String firstMerchantName;

    public PlatformProperties(
            @Value("${platform.mode:SINGLE_SHOP}") String mode,
            @Value("${platform.first-shop.code:SHOP-1}") String firstShopCode,
            @Value("${platform.first-shop.name:}") String firstShopName,
            @Value("${platform.first-merchant.legal-name:}") String firstMerchantName) {

        this.mode = parse(mode);
        this.firstShopCode = firstShopCode;
        this.firstShopName = firstShopName;
        this.firstMerchantName = firstMerchantName;
    }

    /**
     * An unrecognised value falls back to SINGLE_SHOP rather than throwing.
     *
     * Failing closed matters more than failing loudly here: a typo in an
     * environment variable must not be able to put a deployment into a mode
     * it was not configured for, and SINGLE_SHOP is the mode that changes
     * nothing.
     */
    private static PlatformMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PlatformMode.SINGLE_SHOP;
        }
        try {
            return PlatformMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return PlatformMode.SINGLE_SHOP;
        }
    }

    public PlatformMode getMode() { return mode; }
    public String getFirstShopCode() { return firstShopCode; }
    public String getFirstShopName() { return firstShopName; }
    public String getFirstMerchantName() { return firstMerchantName; }
}
