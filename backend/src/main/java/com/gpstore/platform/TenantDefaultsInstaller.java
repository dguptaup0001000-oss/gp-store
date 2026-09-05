package com.gpstore.platform;

import org.springframework.stereotype.Component;

/**
 * Hands {@link TenantDefaults} the two facts an entity listener cannot be
 * injected with.
 *
 * WHY A BEAN WHOSE ONLY JOB IS ITS CONSTRUCTOR. The values have to be in place
 * before anything writes a shop-owned row, and "anything" includes a scheduled
 * job that starts the moment the context refreshes - which is earlier than an
 * ApplicationRunner. Installing during bean creation puts them in before any
 * of that can run, and passing a supplier rather than an id means no database
 * is touched until a row actually needs a shop.
 */
@Component
public class TenantDefaultsInstaller {

    public TenantDefaultsInstaller(PlatformProperties platform, ShopRepository shops) {
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).map(Shop::getId).orElse(null));
    }
}
