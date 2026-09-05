package com.gpstore.catalog.shop;

import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the one-shop invariant true: everything priced in the catalogue is on
 * Shop #1's shelf.
 *
 * WHY THIS EXISTS RATHER THAN TRUST. V48 listed every priced variant, and every
 * path that creates or reprices one lists it too. Between those two facts the
 * invariant should hold on its own - and "should hold on its own" is exactly
 * the kind of claim that is true until somebody adds a fourth way to create a
 * variant. This is a few milliseconds at startup that makes the claim true
 * again instead of finding out from a shopkeeper whose new product never
 * appeared.
 *
 * SINGLE_SHOP ONLY, and that is not a shortcut. In a marketplace "not listed"
 * is a real answer - it means this shop does not sell it - and auto-listing
 * every merchant with every product in the catalogue would put items on shelves
 * their owners never chose to stock.
 *
 * IT ONLY ADDS. An existing listing is never rewritten: once a shopkeeper has
 * set their own price, the catalogue default has no business overwriting it on
 * the next restart.
 */
@Component
public class ShopCatalogReconciliation implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopCatalogReconciliation.class);

    private final JdbcTemplate jdbc;
    private final ShopRepository shops;
    private final PlatformProperties platform;

    public ShopCatalogReconciliation(JdbcTemplate jdbc, ShopRepository shops, PlatformProperties platform) {
        this.jdbc = jdbc;
        this.shops = shops;
        this.platform = platform;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (platform.getMode().isMultiShop()) {
            return;
        }
        Long shopId = shops.findByCode(platform.getFirstShopCode()).map(Shop::getId).orElse(null);
        if (shopId == null) {
            log.warn("Shop #1 ('{}') is missing, so the catalogue cannot be reconciled onto its shelf.",
                    platform.getFirstShopCode());
            return;
        }

        int listed = jdbc.update("""
                INSERT INTO shop_product_variants
                    (shop_id, product_variant_id, selling_price, cost_price, mrp,
                     available, active, display_order)
                SELECT ?, v.id, v.selling_price, v.cost_price, v.mrp,
                       coalesce(v.available, TRUE), coalesce(v.active, TRUE), v.display_order
                FROM product_variants v
                WHERE v.selling_price IS NOT NULL
                  AND v.selling_price > 0
                  AND NOT EXISTS (SELECT 1 FROM shop_product_variants s
                                  WHERE s.shop_id = ? AND s.product_variant_id = v.id)
                """, shopId, shopId);

        if (listed > 0) {
            log.info("Listed {} catalogue variants that Shop #1 was not yet selling.", listed);
        }
    }
}
