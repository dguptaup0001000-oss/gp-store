package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Coupon;
import com.gpstore.entity.IdempotencyRecord;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.enums.DiscountType;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CouponRepository;
import com.gpstore.repository.IdempotencyRecordRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real concurrency, against a real Postgres (see .github/workflows/ci.yml's
 * postgres service container) - these tests only mean anything against a
 * real DB, since the row locks and unique constraints under test don't
 * exist in a mocked repository. Every test fires many threads at the same
 * protected resource at once and asserts on the resulting DB state, not
 * just that no exception was thrown - a race condition can "succeed" from
 * every individual caller's point of view and still leave the data wrong,
 * which is exactly the failure mode this class exists to catch.
 */
@SpringBootTest
class ConcurrencyIntegrationTest {

    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponService couponService;
    @Autowired private IdempotencyRecordRepository idempotencyRecordRepository;

    /**
     * The exact scenario from the scalability audit: stock = 10, 20
     * "customers" all try to buy 1 unit at the same instant. Without a real
     * row lock, a naive "if (stock >= qty) stock -= qty" can let every one
     * of the 20 pass the check before any of them saves, overselling by up
     * to 2x. InventoryService.decrementForPurchase uses
     * PESSIMISTIC_WRITE (see InventoryRepository.findByProductVariantIdForUpdate),
     * so exactly 10 should succeed and the other 10 should see it as out of
     * stock - never both succeeding on the same unit.
     */
    @Test
    void concurrentPurchasesNeverOversellInventory() throws InterruptedException {
        Long productVariantId = createProductVariant("Overselling Test Item");
        Inventory inventory = new Inventory();
        inventory.setProductVariant(productVariantRepository.findById(productVariantId).orElseThrow());
        inventory.setStock(10);
        inventory = inventoryRepository.save(inventory);

        int attackers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    inventoryService.decrementForPurchase(productVariantId, 1);
                    successCount.incrementAndGet();
                } catch (Exception expectedWhenOutOfStock) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All purchase attempts should finish within 30s");
        pool.shutdown();

        Inventory after = inventoryRepository.findById(inventory.getId()).orElseThrow();

        assertEquals(10, successCount.get(), "Exactly 10 of the 20 concurrent attempts should succeed - one per unit of real stock");
        assertEquals(10, rejectedCount.get(), "The other 10 must be rejected as out of stock, not silently oversold");
        assertEquals(0, after.getStock(), "Final stock must be exactly 0 - never negative (oversold) and never left over");
    }

    /**
     * The other half of Phase 5/7: two requests carrying the same
     * Idempotency-Key (a double-tap on Place Order, or a client retry after
     * a network timeout) must not both succeed in creating a record for the
     * same (customerId, key) pair - see IdempotencyRecord's own doc comment
     * on why the unique DB constraint, not an app-level check-then-insert,
     * is what actually makes this safe under a genuine race.
     */
    @Test
    void concurrentDuplicateIdempotencyKeysOnlyOneWins() throws InterruptedException {
        Long customerId = 999_999_001L;
        String idempotencyKey = "test-key-" + System.nanoTime();

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();

                    IdempotencyRecord record = new IdempotencyRecord();
                    record.setCustomerId(customerId);
                    record.setIdempotencyKey(idempotencyKey);
                    record.setCreatedAt(LocalDateTime.now());
                    idempotencyRecordRepository.saveAndFlush(record);
                    successCount.incrementAndGet();
                } catch (DataIntegrityViolationException expectedForEveryLoser) {
                    rejectedCount.incrementAndGet();
                } catch (Exception unexpected) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All idempotency-key attempts should finish within 30s");
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one of the 10 concurrent same-key requests may create a record");
        assertEquals(9, rejectedCount.get(), "The other 9 must fail the unique constraint, not each create their own duplicate");

        long rowsWithThisKey = idempotencyRecordRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).isPresent() ? 1 : 0;
        assertEquals(1, rowsWithThisKey, "Only one row should exist in the DB for this (customerId, key) pair");
    }

    /**
     * A limited-use coupon (usageLimit = 1) redeemed by many concurrent
     * checkouts at once - CouponService.redeem() locks the coupon row
     * (findByCouponCodeForUpdate) before re-validating and incrementing
     * usedCount, which is what should stop more than one of these from
     * squeezing past the limit.
     */
    @Test
    void concurrentCouponRedemptionsRespectUsageLimit() throws InterruptedException {
        Coupon coupon = new Coupon();
        coupon.setCouponCode("RACE" + System.nanoTime());
        coupon.setActive(true);
        coupon.setDiscountType(DiscountType.FLAT);
        coupon.setDiscountValue(new BigDecimal("10"));
        coupon.setUsageLimit(1);
        coupon.setUsedCount(0);
        coupon.setExpiryDate(LocalDate.now().plusDays(1));
        coupon = couponRepository.save(coupon);
        String code = coupon.getCouponCode();

        int attackers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch ready = new CountDownLatch(attackers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attackers);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < attackers; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    couponService.redeem(code, new BigDecimal("100"));
                    successCount.incrementAndGet();
                } catch (Exception expectedOnceLimitReached) {
                    rejectedCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "All redemption attempts should finish within 30s");
        pool.shutdown();

        assertEquals(1, successCount.get(), "Only one of the 10 concurrent redemptions may succeed against usageLimit=1");
        assertEquals(9, rejectedCount.get());

        Coupon after = couponRepository.findByCouponCodeIgnoreCase(code).orElseThrow();
        assertEquals(1, after.getUsedCount(), "usedCount must land at exactly 1, never overshoot the limit");
    }

    private Long createProductVariant(String namePrefix) {
        Category category = new Category();
        category.setName("Concurrency Test Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName(namePrefix + " " + System.nanoTime());
        product.setBrand("TestBrand");
        product.setActive(true);
        product.setCategory(category);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setQuantity(1.0);
        variant.setUnit("pc");
        variant.setMrp(new BigDecimal("100"));
        variant.setSellingPrice(new BigDecimal("90"));
        variant.setAvailable(true);
        variant.setActive(true);
        variant = productVariantRepository.save(variant);

        return variant.getId();
    }
}
