package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.InventoryRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deadlock avoidance: every path that locks inventory rows must lock them
 * in the SAME global order, ascending variant id.
 *
 * placeOrder has sorted its cart that way for a while. Inventory
 * restoration did not - it walked the order's items in whatever order the
 * database handed them back. That asymmetry is a real deadlock, not a
 * theoretical one:
 *
 *   order A holds [5, 3] and is being restored (cancellation or UPI expiry)
 *   cart  B holds [3, 5] and is checking out
 *
 * restoration locks 5 and waits for 3; the checkout locks 3 and waits for
 * 5. Postgres breaks the tie by killing one of them, so it surfaces as a
 * cancellation or a checkout that randomly fails under load - the kind of
 * thing that never reproduces on demand.
 *
 * Holding the ORDER row lock first does not help here. It serialises two
 * restorations of the SAME order, but the contention above is between a
 * restoration and an unrelated checkout that share only INVENTORY rows.
 *
 * This test asserts the ordering directly rather than trying to provoke the
 * deadlock: racing two threads for a real deadlock is timing-dependent and
 * would be flaky in CI, whereas "which rows did it lock, in what sequence"
 * is exactly the invariant that has to hold and is fully deterministic.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
class InventoryLockOrderingTest {

    // Spy, not mock: the real locking behaviour must still run - this only
    // records which variant ids it was asked to lock and in what sequence.
    @MockitoSpyBean private InventoryService inventoryService;

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository productVariantRepository;

    @Test
    void restorationLocksInventoryRowsInAscendingVariantIdOrder() {
        // Items are inserted in DESCENDING variant id on purpose. An
        // unsorted restoration walks them in that order, which is the exact
        // reverse of what checkout does - so this fixture fails against the
        // old code and passes only once restoration sorts.
        List<Long> variantIds = threeVariantsWithStock();
        List<Long> descending = new ArrayList<>(variantIds);
        descending.sort((a, b) -> Long.compare(b, a));

        Long orderId = orderWithItemsInGivenOrder(descending);

        Mockito.clearInvocations(inventoryService);
        orderService.restoreInventoryForOrder(orderId);

        List<Long> locked = lockedVariantIdsInCallOrder();

        assertEquals(variantIds.size(), locked.size(),
                "Every line item's inventory row must be locked exactly once");

        List<Long> ascending = new ArrayList<>(variantIds);
        ascending.sort(Long::compare);
        assertEquals(ascending, locked,
                "Inventory locks must be taken in ascending variant id order - the same "
                        + "order placeOrder uses - or a restoration and a concurrent "
                        + "checkout sharing these variants can deadlock");
    }

    /**
     * The ordering has to survive the guard as well: a restoration that is
     * a no-op (the stock already went back) must take no inventory locks at
     * all, rather than locking every row and then deciding it had nothing
     * to do.
     */
    @Test
    void alreadyRestoredOrderTakesNoInventoryLocks() {
        List<Long> variantIds = threeVariantsWithStock();
        Long orderId = orderWithItemsInGivenOrder(variantIds);

        orderService.restoreInventoryForOrder(orderId);

        Mockito.clearInvocations(inventoryService);
        boolean restoredAgain = orderService.restoreInventoryForOrder(orderId);

        assertTrue(!restoredAgain, "A second restore must report that it did nothing");
        assertEquals(List.of(), lockedVariantIdsInCallOrder(),
                "A no-op restore must not lock inventory rows it will not touch");
    }

    /**
     * Restoration now names the SHOP as well as the variant.
     *
     * It runs from the payment-expiry sweep, which spans shops and has no
     * filter enabled, so the shop is read off the order being restored rather
     * than left to whichever row the query found first. The two-argument
     * overload is what the restore path calls; the ordering invariant this
     * test exists for is unchanged.
     */
    private List<Long> lockedVariantIdsInCallOrder() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(inventoryService, Mockito.atLeast(0))
                .getByProductVariantForUpdate(captor.capture(), Mockito.any());
        return captor.getAllValues();
    }

    private List<Long> threeVariantsWithStock() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long variantId = createProductVariant();
            Inventory inventory = new Inventory();
            inventory.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
            inventory.setStock(10);
            inventoryRepository.save(inventory);
            ids.add(variantId);
        }
        return ids;
    }

    private Long orderWithItemsInGivenOrder(List<Long> variantIdsInInsertionOrder) {
        Customer customer = new Customer();
        customer.setFullName("Lock Order Customer");
        customer.setEmail("lock-order-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.valueOf(System.nanoTime()).substring(0, 9));
        customer.setPassword("irrelevant-for-this-test");
        customer.setEnabled(true);
        customer.setActive(true);
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setOrderNumber("LOCKORDER-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setOrderStatus(OrderStatus.CONFIRMED);
        order.setOrderDate(LocalDateTime.now());
        order.setActive(true);
        order = orderRepository.save(order);

        for (Long variantId : variantIdsInInsertionOrder) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductVariant(productVariantRepository.findById(variantId).orElseThrow());
            item.setQuantity(1);
            item.setPrice(new BigDecimal("90.00"));
            item.setTotalPrice(new BigDecimal("90.00"));
            item.setActive(true);
            orderItemRepository.saveAndFlush(item);
        }

        return order.getId();
    }

    private Long createProductVariant() {
        Category category = new Category();
        category.setName("Lock Order Category " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName("Lock Order Item " + System.nanoTime());
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
