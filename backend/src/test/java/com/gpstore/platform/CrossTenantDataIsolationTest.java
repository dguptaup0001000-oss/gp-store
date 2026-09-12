package com.gpstore.platform;

import com.gpstore.entity.Coupon;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Order;
import com.gpstore.repository.CouponRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two shops, one database, and neither may see the other.
 *
 * THIS IS THE TEST THAT DECIDES WHETHER THE MARKETPLACE CAN EXIST. Everything
 * else in the transformation is features; this is the one where being wrong
 * means a kirana owner reads a competitor's order book. So it is written the
 * way an attacker would probe rather than the way the UI happens to call:
 * every check has a positive half (the shop CAN see its own) and a negative
 * half (the shop CANNOT see the other's), and the negative half goes at the
 * data through ids it was never given.
 *
 * THE FIXTURE IS DELIBERATELY THE NASTIEST SHAPE. One customer with an order
 * in BOTH shops - which is exactly what a real marketplace produces the first
 * week it runs, and exactly the case a naive "filter by customer" would leak.
 *
 * WHAT THIS DOES NOT CLAIM. Products are a shared central catalogue by design
 * (§10) and have no shop_id at all, so there is no such thing as "Shop A's
 * product" to isolate yet - per-shop offerings arrive with SHOP_PRODUCT in a
 * later slice. What is shop-owned today, and therefore what is proved here,
 * is orders, workers, coupons and inventory.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("One shop cannot reach another shop's data")
class CrossTenantDataIsolationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopRepository shops;
    @Autowired private MerchantRepository merchants;
    @Autowired private PlatformProperties platform;
    @Autowired private OrderRepository orders;
    @Autowired private CouponRepository coupons;
    @Autowired private DeliveryPartnerRepository partners;
    @Autowired private InventoryRepository inventory;

    private long shopA;
    private long shopB;
    private Long merchantB;
    private Long customerId;

    private long orderAId;
    private long orderBId;
    private String orderANumber;
    private String orderBNumber;
    private long couponAId;
    private long couponBId;
    private String couponACode;
    private String couponBCode;
    private long partnerAId;
    private long partnerBId;
    private long variantlessInventoryA;
    private long variantlessInventoryB;

    private final String tag = "xt" + System.nanoTime();

    @BeforeEach
    void twoShopsWithDataOfTheirOwn() {
        shopA = shops.findByCode(platform.getFirstShopCode()).orElseThrow().getId();

        Merchant second = new Merchant();
        second.setLegalName("Cross-tenant fixture merchant " + tag);
        second.setDisplayName("Fixture B");
        second.setStatus(MerchantStatus.ACTIVE);
        second.setIsDemo(Boolean.TRUE);
        second.setActive(Boolean.TRUE);
        merchantB = merchants.save(second).getId();

        Shop b = new Shop();
        b.setMerchantId(merchantB);
        b.setCode("XT-" + tag);
        b.setDisplayName("Fixture shop B");
        b.setStatus(ShopStatus.ACTIVE);
        b.setIsDemo(Boolean.TRUE);
        b.setActive(Boolean.TRUE);
        shopB = shops.save(b).getId();

        jdbc.update("""
                INSERT INTO customers (full_name, email, mobile_number, password, role, active)
                VALUES (?, ?, ?, 'not-a-real-hash', 'CUSTOMER', true)
                """,
                "Cross-tenant fixture " + tag,
                tag + "@example.test",
                "9" + (100000000 + (int) (Math.random() * 899999999)));
        customerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, tag + "@example.test");

        orderANumber = "XTA-" + tag;
        orderBNumber = "XTB-" + tag;
        orderAId = insertOrder(orderANumber, shopA);
        orderBId = insertOrder(orderBNumber, shopB);

        couponACode = "XTA" + tag;
        couponBCode = "XTB" + tag;
        couponAId = insertCoupon(couponACode, shopA);
        couponBId = insertCoupon(couponBCode, shopB);

        partnerAId = insertPartner("Rider A " + tag, shopA);
        partnerBId = insertPartner("Rider B " + tag, shopB);

        variantlessInventoryA = insertInventory(shopA, 11);
        variantlessInventoryB = insertInventory(shopB, 22);
    }

    @AfterEach
    void removeTheFixture() {
        jdbc.update("DELETE FROM inventory WHERE id in (?, ?)", variantlessInventoryA, variantlessInventoryB);
        jdbc.update("DELETE FROM delivery_partners WHERE id in (?, ?)", partnerAId, partnerBId);
        jdbc.update("DELETE FROM coupons WHERE id in (?, ?)", couponAId, couponBId);
        jdbc.update("DELETE FROM coupons WHERE coupon_code like ?", "XTW" + tag + "%");
        jdbc.update("DELETE FROM orders WHERE id in (?, ?)", orderAId, orderBId);
        if (customerId != null) {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        jdbc.update("DELETE FROM shops WHERE id = ?", shopB);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantB);
    }

    // ------------------------------------------------------------ positive

    @Test
    @DisplayName("each shop sees its own orders - including for a customer who shops at both")
    void eachShopSeesItsOwnOrders() {
        List<Order> seenByA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> orders.findByCustomerId(customerId));
        List<Order> seenByB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> orders.findByCustomerId(customerId));

        assertTrue(numbers(seenByA).contains(orderANumber),
                "Shop A must still see its own order - isolation that hides your own orders is an outage");
        assertTrue(numbers(seenByB).contains(orderBNumber),
                "Shop B must see its own order");

        assertFalse(numbers(seenByA).contains(orderBNumber),
                "Shop A read an order belonging to Shop B, for a customer they share");
        assertFalse(numbers(seenByB).contains(orderANumber),
                "Shop B read an order belonging to Shop A");
    }

    @Test
    @DisplayName("each shop sees its own workers and none of the other's")
    void workersDoNotCrossShops() {
        List<String> byA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> partners.findByDeletedAtIsNull(Sort.by("id")).stream().map(DeliveryPartner::getName).toList());
        List<String> byB = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> partners.findByDeletedAtIsNull(Sort.by("id")).stream().map(DeliveryPartner::getName).toList());

        assertTrue(byA.contains("Rider A " + tag), "Shop A lost sight of its own rider");
        assertTrue(byB.contains("Rider B " + tag), "Shop B lost sight of its own rider");
        assertFalse(byA.contains("Rider B " + tag), "Shop A can see Shop B's rider roster");
        assertFalse(byB.contains("Rider A " + tag), "Shop B can see Shop A's rider roster");
    }

    @Test
    @DisplayName("a coupon code is looked up inside one shop only")
    void couponCodesDoNotCrossShops() {
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> coupons.findByCouponCodeIgnoreCase(couponACode)).isPresent(),
                "Shop A must still be able to redeem its own coupon");

        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> coupons.findByCouponCodeIgnoreCase(couponBCode)).isEmpty(),
                "a customer could spend Shop B's discount at Shop A by typing the code");
    }

    @Test
    @DisplayName("stock levels belong to the shop holding the stock")
    void inventoryDoesNotCrossShops() {
        Integer aFromA = TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> inventory.findById(variantlessInventoryA).map(i -> i.getStock()).orElse(null));
        assertEquals(11, aFromA, "Shop A cannot read its own stock");

        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA),
                        () -> inventory.findById(variantlessInventoryB)),
                "Shop A read Shop B's stock level by id");
    }

    // ------------------------------------------------------------ negative,
    // reached the way an attacker reaches it: by id, not through a screen

    @Test
    @DisplayName("a guessed order id from another shop is refused, not returned")
    void readingAnotherShopsOrderByIdIsRefused() {
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> orders.findById(orderAId)).isPresent(), "Shop A cannot open its own order");

        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> orders.findById(orderBId)),
                "changing the id in a URL handed Shop A an order belonging to Shop B");
    }

    @Test
    @DisplayName("the shop-scoped detail query returns nothing for another shop's order")
    void theDetailQueryIsFilteredToo() {
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> orders.findByIdWithDetails(orderBId)).isPresent());

        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> orders.findByIdWithDetails(orderAId)).isEmpty(),
                "the query behind GET /api/orders/{id} returned another shop's order");
    }

    @Test
    @DisplayName("another shop's row cannot be modified, because it cannot be loaded")
    void modifyingAnotherShopsRowIsRefused() {
        assertThrows(CrossShopAccessException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                    Coupon stolen = coupons.findById(couponBId).orElseThrow();
                    stolen.setDiscountValue(new BigDecimal("99.00"));
                    return coupons.save(stolen);
                }),
                "Shop A rewrote Shop B's coupon");

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM coupons WHERE id = ? AND discount_value = 99.00", Integer.class, couponBId),
                "Shop B's coupon was altered despite the refusal");
    }

    @Test
    @DisplayName("another shop's row cannot be deleted")
    void deletingAnotherShopsRowIsRefused() {
        assertThrows(RuntimeException.class,
                () -> TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
                    orders.deleteById(orderBId);
                    return null;
                }),
                "Shop A was allowed to attempt a delete of Shop B's order");

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE id = ?", Integer.class, orderBId),
                "Shop B's order was actually deleted by Shop A");
    }

    // -------------------------------------------------------------- writes

    @Test
    @DisplayName("a new row is stamped with the shop in scope, not the shop on the object")
    void theShopOnAnIncomingObjectIsIgnored() {
        Coupon smuggled = new Coupon();
        smuggled.setCouponCode("XTW" + tag + "1");
        smuggled.setDiscountValue(new BigDecimal("5.00"));
        // A request body, a DTO mapper or a copy-constructor naming somebody
        // else's shop. It must not decide where the row lands.
        smuggled.setShopId(shopA);

        Long written = TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> coupons.save(smuggled).getId());

        assertEquals(shopB, jdbc.queryForObject(
                "SELECT shop_id FROM coupons WHERE id = ?", Long.class, written),
                "a shop id carried on the object overrode the shop the credential resolved to");
    }

    @Test
    @DisplayName("a row created inside a shop is invisible to the other shop immediately")
    void aNewRowIsIsolatedFromTheMomentItExists() {
        String code = "XTW" + tag + "2";
        TenantContext.runWithin(TenantScope.ofShop(shopB), () -> {
            Coupon fresh = new Coupon();
            fresh.setCouponCode(code);
            fresh.setDiscountValue(new BigDecimal("7.00"));
            return coupons.save(fresh);
        });

        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopB),
                () -> coupons.findByCouponCodeIgnoreCase(code)).isPresent());
        assertTrue(TenantContext.runWithin(TenantScope.ofShop(shopA),
                () -> coupons.findByCouponCodeIgnoreCase(code)).isEmpty(),
                "a coupon created by Shop B was immediately readable by Shop A");
    }

    // ------------------------------------------------- work that spans shops

    @Test
    @DisplayName("platform-wide work still sees every shop, which is what makes the sweeps work")
    void platformScopeIsNotFilteredAndThatIsDeliberate() {
        List<String> everything = TenantContext.runWithin(TenantScope.platform(),
                () -> numbers(orders.findByCustomerId(customerId)));

        assertTrue(everything.contains(orderANumber));
        assertTrue(everything.contains(orderBNumber),
                "the outbox drain, the stuck-refund sweep and the late-delivery flagger all run "
                        + "platform-wide; if this list is filtered they silently stop working for "
                        + "every shop but one");
    }

    @Test
    @DisplayName("a shop scope is not something the caller can widen from inside")
    void aShopScopeCannotBeWidenedFromWithin() {
        // Nesting platform() inside a shop scope is how a leak would be
        // written if somebody wanted one; it is legal Java, so what stops it
        // is that nothing on the request path calls it - and the moment the
        // nested block ends the shop scope must be back.
        TenantContext.runWithin(TenantScope.ofShop(shopA), () -> {
            assertEquals(shopA, TenantContext.require().requireShopId());
            TenantContext.runWithin(TenantScope.platform(),
                    () -> assertTrue(TenantContext.require().isPlatform()));
            assertEquals(shopA, TenantContext.require().requireShopId(),
                    "the shop scope was not restored after platform work nested inside it");
            return null;
        });
        assertFalse(TenantContext.isSet(), "the scope outlived the block that opened it");
    }

    // ------------------------------------------------------------- fixtures

    private long insertOrder(String number, long shopId) {
        jdbc.update("""
                INSERT INTO orders (customer_id, order_number, total_amount, order_status,
                                    payment_status, order_date, shop_id)
                VALUES (?, ?, ?, 'PENDING_CONFIRMATION', 'PENDING', now(), ?)
                """, customerId, number, new BigDecimal("100.00"), shopId);
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, number);
    }

    private long insertCoupon(String code, long shopId) {
        jdbc.update("""
                INSERT INTO coupons (coupon_code, discount_value, active, shop_id)
                VALUES (?, 10.00, true, ?)
                """, code, shopId);
        return jdbc.queryForObject("SELECT id FROM coupons WHERE coupon_code = ?", Long.class, code);
    }

    private long insertPartner(String name, long shopId) {
        jdbc.update("""
                INSERT INTO delivery_partners (name, mobile, available, active, shop_id)
                VALUES (?, ?, true, true, ?)
                """, name, "8" + (100000000 + (int) (Math.random() * 899999999)), shopId);
        return jdbc.queryForObject(
                "SELECT id FROM delivery_partners WHERE name = ?", Long.class, name);
    }

    private long insertInventory(long shopId, int stock) {
        jdbc.update("INSERT INTO inventory (stock, reserved_stock, shop_id) VALUES (?, 0, ?)", stock, shopId);
        return jdbc.queryForObject(
                "SELECT max(id) FROM inventory WHERE shop_id = ? AND stock = ?", Long.class, shopId, stock);
    }

    private static List<String> numbers(List<Order> list) {
        return list.stream().map(Order::getOrderNumber).toList();
    }
}
