package com.gpstore.catalog.shop;

import com.gpstore.discovery.PreferredShops;
import com.gpstore.entity.Customer;
import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.platform.MerchantStatus;
import com.gpstore.platform.PlatformMode;
import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.ShopStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantDefaults;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.support.TestMobileNumbers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a customer taps ADD on a marketplace card, something has to choose a
 * shop. This is what that choice is allowed to be.
 *
 * <p>The decision has to be defensible to both sides. A customer must not
 * overpay because the app liked a shop; a merchant must not lose a sale to a
 * rule nobody can explain. So the rules here are few and each one is
 * deliberately testable.
 */
@SpringBootTest(properties = {
        "platform.mode=MULTI_SHOP_PRODUCTION",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Which shop supplies it")
class WhichShopSuppliesItTest {

    @Autowired private SellerResolution resolution;
    @Autowired private PreferredShops preferredShops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private CustomerRepository customers;
    @Autowired private PlatformProperties platform;

    private static final double LAT = 21.1111;
    private static final double LNG = 79.2222;

    private final String tag = "sell" + System.nanoTime();
    private Long merchantId;
    private Long customerId;
    private Long categoryId;
    private Long variantId;
    private Long productId;
    private final List<Long> shopIds = new ArrayList<>();

    @BeforeEach
    void threeShopsSellingTheSameThing() {
        TenantDefaults.install(PlatformMode.MULTI_SHOP_PRODUCTION,
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());

        Customer customer = new Customer();
        customer.setFullName("Seller Test Customer");
        customer.setEmail("seller-" + tag + "@example.test");
        customer.setMobileNumber(TestMobileNumbers.unique());
        customer.setPassword("irrelevant");
        customer.setEnabled(true);
        customer.setActive(true);
        customerId = customers.save(customer).getId();

        Merchant m = new Merchant();
        m.setLegalName("Seller fixture " + tag);
        m.setDisplayName("Seller fixture");
        m.setStatus(MerchantStatus.ACTIVE);
        m.setIsDemo(Boolean.TRUE);
        m.setActive(Boolean.TRUE);
        merchantId = merchants.save(m).getId();

        jdbc.update("INSERT INTO categories (name, active) VALUES (?, true)", "Mobiles " + tag);
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?",
                Long.class, "Mobiles " + tag);

        jdbc.update("INSERT INTO products (name, brand, active, category_id) VALUES (?, ?, true, ?)",
                "Phone " + tag, "TestBrand", categoryId);
        productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, "Phone " + tag);
        jdbc.update("INSERT INTO product_variants "
                + "(product_id, quantity, unit, mrp, selling_price, available, active) "
                + "VALUES (?, 1, 'pc', 26000, 25000, true, true)", productId);
        variantId = jdbc.queryForObject(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                Long.class, productId);
    }

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
        for (Long shopId : shopIds) {
            jdbc.update("DELETE FROM shop_product_variants WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shop_business_hours WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        jdbc.update("DELETE FROM customer_preferred_shops WHERE customer_id = ?", customerId);
        jdbc.update("DELETE FROM product_variants WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId());
    }

    @Nested
    @DisplayName("The ranking")
    class Ranking {

        @Test
        @DisplayName("every nearby shop that stocks it is offered, none invented")
        void allEligibleSellersAreOffered() {
            sells("Near shop", 0.0008, "25000");
            sells("Mid shop", 0.0030, "24500");
            sells("Far shop", 0.0060, "24000");

            List<SellerOption> options =
                    resolution.sellersFor(customerId, variantId, LAT, LNG, null);

            assertEquals(3, options.size(), "all three shops stock it and all three serve this pin");
        }

        @Test
        @DisplayName("a shop that does not stock it is never offered")
        void aShopThatDoesNotStockItIsNotASeller() {
            sells("Has it", 0.0008, "25000");
            newShop("Does not have it", 0.0010);

            List<SellerOption> options =
                    resolution.sellersFor(customerId, variantId, LAT, LNG, null);

            assertEquals(1, options.size(),
                    "offering a shop that does not sell the product sends the customer to a "
                            + "counter where nobody can help them");
        }

        @Test
        @DisplayName("a Visit-to-Buy listing is not a seller of anything a cart can hold")
        void offlineListingsAreNotSellers() {
            sells("Sells online", 0.0008, "25000");
            Long showroom = newShop("Showroom", 0.0010);
            list(showroom, "24000", CommerceMode.VISIT_TO_BUY);

            List<SellerOption> options =
                    resolution.sellersFor(customerId, variantId, LAT, LNG, null);

            assertEquals(1, options.size(),
                    "the showroom is cheaper and nearer, and routing ADD to it would send the "
                            + "customer towards a purchase the backend then refuses");
            assertFalse(options.stream().anyMatch(o -> showroom.equals(o.shopId())));
        }
    }

    @Nested
    @DisplayName("The customer's own choice")
    class TheCustomersChoice {

        @Test
        @DisplayName("a preferred shop wins even when another is cheaper")
        void preferenceBeatsPrice() {
            Long usual = sells("My usual shop", 0.0030, "25000");
            sells("Cheaper stranger", 0.0008, "23000");

            preferredShops.setForCategory(customerId, categoryId, List.of(usual));

            List<SellerOption> options =
                    resolution.sellersFor(customerId, variantId, LAT, LNG, null);

            assertEquals(usual, options.get(0).shopId(),
                    "THE CUSTOMER ALREADY DECIDED. A preference that two thousand rupees can "
                            + "overturn is not a preference, it is a suggestion the app ignores.");
            assertTrue(options.get(0).preferred());
        }

        @Test
        @DisplayName("first choice beats second choice")
        void theSlotsMeanSomething() {
            Long first = sells("First choice", 0.0040, "25500");
            Long second = sells("Second choice", 0.0008, "25000");

            preferredShops.setForCategory(customerId, categoryId, List.of(first, second));

            List<SellerOption> options =
                    resolution.sellersFor(customerId, variantId, LAT, LNG, null);

            assertEquals(first, options.get(0).shopId(),
                    "slot 1 is the customer's first choice and must outrank slot 2");
            assertEquals(second, options.get(1).shopId());
        }

        @Test
        @DisplayName("a preferred shop that cannot serve today does not break ADD")
        void thereIsAlwaysAFallback() {
            Long usual = newShop("My usual shop", 0.0030);   // stocks nothing
            Long other = sells("Someone else", 0.0008, "25000");

            preferredShops.setForCategory(customerId, categoryId, List.of(usual));

            List<SellerOption> options =
                    resolution.sellersFor(customerId, variantId, LAT, LNG, null);

            assertEquals(other, options.get(0).shopId(),
                    "a customer whose usual shop is out of stock still wants the thing - "
                            + "falling back is the service, refusing is not");
        }

        @Test
        @DisplayName("nobody gets a preference they did not express")
        void anAnonymousCustomerHasNoPreference() {
            sells("Dearer and nearer", 0.0008, "26000");
            Long cheaper = sells("Cheaper", 0.0030, "24000");

            List<SellerOption> options =
                    resolution.sellersFor(null, variantId, LAT, LNG, null);

            assertTrue(options.stream().noneMatch(SellerOption::preferred),
                    "silently inventing a preference for somebody who never chose one is how "
                            + "an app starts deciding where people shop");
            assertEquals(cheaper, options.get(0).shopId(),
                    "with no preference and no delivery difference, the cheaper total wins");
        }
    }

    // ------------------------------------------------------------- fixture

    private Long sells(String name, double latOffset, String price) {
        Long shopId = newShop(name, latOffset);
        list(shopId, price, CommerceMode.ONLINE_PURCHASE);
        return shopId;
    }

    private Long newShop(String name, double latOffset) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setCode((name + "-" + tag).toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        shop.setDisplayName(name + " " + tag);
        shop.setStatus(ShopStatus.ACTIVE);
        shop.setLatitude(LAT + latOffset);
        shop.setLongitude(LNG);
        shop.setMaxDeliveryRadiusKm(new BigDecimal("10"));
        shop.setTimeZone("Asia/Kolkata");
        shop.setIsDemo(Boolean.TRUE);
        shop.setActive(Boolean.TRUE);
        Long id = shops.save(shop).getId();
        shopIds.add(id);
        return id;
    }

    private void list(Long shopId, String price, CommerceMode mode) {
        jdbc.update("INSERT INTO shop_product_variants "
                        + "(shop_id, product_variant_id, selling_price, mrp, available, active, "
                        + " commerce_mode, price_mode, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 26000, true, true, ?, ?, now(), now())",
                shopId, variantId, new BigDecimal(price), mode.name(),
                mode.isBuyableOnline() ? "EXACT_PRICE" : "STARTING_FROM");
    }
}
