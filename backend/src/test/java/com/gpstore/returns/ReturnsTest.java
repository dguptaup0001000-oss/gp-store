package com.gpstore.returns;

import com.gpstore.entity.*;
import com.gpstore.enums.*;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.payment.gateway.PaymentGateway;
import com.gpstore.payment.gateway.PaymentGateway.GatewayRefund;
import com.gpstore.repository.*;
import com.gpstore.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Taking goods back, and paying for them exactly once.
 *
 * THE INVARIANT UNDER TEST: a customer can never be refunded for more units
 * than they bought, or at a price they did not pay. The request names lines
 * and quantities; the money is computed here from the order's own stored
 * prices, because the request arrives from a phone and the amount is the
 * shop's money.
 */
@SpringBootTest(properties = {
        "cashfree.webhook-secret=returns-test-secret",
        "refund.reconcile-initial-delay-ms=3600000",
        "refund.reconcile-interval-ms=3600000",
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "payment.expiry-interval-ms=3600000"
})
@DisplayName("Returns")
class ReturnsTest {

    @Autowired private ReturnService returnService;
    @Autowired private OrderReturnRepository returnRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryService inventoryService;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private com.gpstore.repository.RefundRepository refundRepository;

    @MockitoSpyBean private PaymentGateway gateway;

    /** Two lines: 3 x 50.00 and 2 x 100.00. Order total 350.00. */
    private record Fixture(Order order, Customer customer, OrderItem cheap, OrderItem dear, Payment payment) {}

    @Test
    @DisplayName("the refund is the returned lines' own price, not a number the client sent")
    void refundComesFromTheOrder() {
        Fixture f = delivered();
        stubRefund(f.payment(), GatewayRefund.State.SUCCEEDED);

        OrderReturn request = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 2), "opened one, atta was damp");

        returnService.approve(request.getId(), null);

        OrderReturn after = returnRepository.findById(request.getId()).orElseThrow();
        assertEquals(OrderReturn.Status.APPROVED, after.getStatus());
        assertEquals(0, new BigDecimal("100.00").compareTo(after.getRefundAmount()),
                "2 units at the 50.00 the customer was actually charged.");
    }

    @Test
    @DisplayName("approval refunds through the ledger, so the payment agrees")
    void approvalOpensARealRefund() {
        Fixture f = delivered();
        stubRefund(f.payment(), GatewayRefund.State.SUCCEEDED);

        OrderReturn request = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.dear().getId(), 1), null);
        returnService.approve(request.getId(), null);

        assertEquals(1, refundRepository.countByPaymentId(f.payment().getId()));
        assertEquals(0, new BigDecimal("100.00")
                .compareTo(refundRepository.settledFor(f.payment().getId())));

        Payment payment = paymentRepository.findById(f.payment().getId()).orElseThrow();
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getPaymentStatus(),
                "100 of 350 went back, so the payment is partly refunded - not refunded.");
    }

    @Test
    @DisplayName("two returns on one order both refund, and the totals add up")
    void twoReturnsOnOneOrder() {
        Fixture f = delivered();
        stubRefund(f.payment(), GatewayRefund.State.SUCCEEDED);

        returnService.approve(returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 1), null).getId(), null);
        returnService.approve(returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.dear().getId(), 1), null).getId(), null);

        // 50 + 100. Before refunds were their own rows the second would have
        // overwritten the first and the books would have said 100.
        assertEquals(0, new BigDecimal("150.00")
                .compareTo(refundRepository.settledFor(f.payment().getId())));
        assertEquals(2, refundRepository.countByPaymentId(f.payment().getId()));
    }

    @Test
    @DisplayName("a customer cannot return more units than they bought")
    void cannotReturnMoreThanWasBought() {
        Fixture f = delivered();

        assertThrows(ConflictException.class, () -> returnService.request(
                f.customer().getId(), f.order().getId(), Map.of(f.cheap().getId(), 4), null),
                "Three were bought. A fourth must not be refundable.");
    }

    @Test
    @DisplayName("units already asked for are not available to ask for again")
    void unitsAreClaimedByAPendingRequest() {
        Fixture f = delivered();

        returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 3), null);

        // The first request has not been decided yet. Letting a second claim
        // the same units would put two returns in the queue for one item -
        // approve both and the shop refunds twice for goods it received once.
        assertThrows(ConflictException.class, () -> returnService.request(
                f.customer().getId(), f.order().getId(), Map.of(f.cheap().getId(), 1), null));
    }

    @Test
    @DisplayName("a rejected return releases its units")
    void rejectionReleasesTheUnits() {
        Fixture f = delivered();

        OrderReturn first = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 3), null);
        returnService.reject(first.getId(), null, "the packet was opened and used");

        // Refusing is what makes the units available again - otherwise one
        // refused request would lock that line forever.
        assertDoesNotThrow(() -> returnService.request(
                f.customer().getId(), f.order().getId(), Map.of(f.cheap().getId(), 3), null));
    }

    @Test
    @DisplayName("a rejection has to say why")
    void rejectionNeedsAReason() {
        Fixture f = delivered();
        OrderReturn request = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 1), null);

        assertThrows(BadRequestException.class,
                () -> returnService.reject(request.getId(), null, "  "));
    }

    @Test
    @DisplayName("one customer cannot return another customer's order")
    void cannotReturnSomebodyElsesOrder() {
        Fixture f = delivered();
        Customer stranger = customer();

        // Not "forbidden" - a stranger must not be able to learn the order
        // exists from the shape of the refusal.
        assertThrows(ResourceNotFoundException.class, () -> returnService.request(
                stranger.getId(), f.order().getId(), Map.of(f.cheap().getId(), 1), null));
    }

    @Test
    @DisplayName("a line from somebody else's order cannot be smuggled in")
    void cannotReturnALineFromAnotherOrder() {
        Fixture mine = delivered();
        Fixture theirs = delivered();

        // The most valuable line in the shop, named against my own order.
        // Without the ownership check on the LINE this refunds their price
        // to me.
        assertThrows(BadRequestException.class, () -> returnService.request(
                mine.customer().getId(), mine.order().getId(),
                Map.of(theirs.dear().getId(), 1), null));
    }

    @Test
    @DisplayName("only a delivered order can be returned")
    void onlyDeliveredOrders() {
        Fixture f = delivered();
        Order order = orderRepository.findById(f.order().getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
        orderRepository.save(order);

        assertThrows(ConflictException.class, () -> returnService.request(
                f.customer().getId(), f.order().getId(), Map.of(f.cheap().getId(), 1), null));
    }

    @Test
    @DisplayName("the window closes")
    void theWindowCloses() {
        Fixture f = delivered();
        Delivery delivery = deliveryRepository.findByOrderId(f.order().getId()).orElseThrow();
        delivery.setDeliveredAt(LocalDateTime.now().minusDays(30));
        deliveryRepository.save(delivery);

        ConflictException tooLate = assertThrows(ConflictException.class, () -> returnService.request(
                f.customer().getId(), f.order().getId(), Map.of(f.cheap().getId(), 1), null));
        assertTrue(tooLate.getMessage().contains("days after delivery"), tooLate.getMessage());
    }

    @Test
    @DisplayName("stock comes back on approval, not on the request")
    void stockReturnsOnApprovalOnly() {
        Fixture f = delivered();
        stubRefund(f.payment(), GatewayRefund.State.SUCCEEDED);

        Long variantId = f.cheap().getProductVariant().getId();
        int before = inventoryService.getByProductVariant(variantId).getStock();

        OrderReturn request = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 2), null);

        assertEquals(before, inventoryService.getByProductVariant(variantId).getStock(),
                "The items are still in the customer's house. Counting them as "
                        + "sellable is how a shop promises stock it does not have.");

        returnService.approve(request.getId(), null);

        assertEquals(before + 2, inventoryService.getByProductVariant(variantId).getStock());
    }

    @Test
    @DisplayName("a decided return cannot be decided again")
    void decidedOnce() {
        Fixture f = delivered();
        stubRefund(f.payment(), GatewayRefund.State.SUCCEEDED);

        OrderReturn request = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 1), null);
        returnService.approve(request.getId(), null);

        // Two admins pressing Approve at the same moment: the row lock makes
        // the second wait, and this is what it must see when it wakes.
        assertThrows(ConflictException.class, () -> returnService.approve(request.getId(), null));
    }

    @Test
    @DisplayName("a customer can cancel their own request, and only their own")
    void cancelIsOwnerOnly() {
        Fixture f = delivered();
        OrderReturn request = returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 1), null);

        Customer stranger = customer();
        assertThrows(ResourceNotFoundException.class,
                () -> returnService.cancel(request.getId(), stranger.getId()));

        OrderReturn cancelled = returnService.cancel(request.getId(), f.customer().getId());
        assertEquals(OrderReturn.Status.CANCELLED, cancelled.getStatus());
    }

    @Test
    @DisplayName("what is still returnable is what the app is told")
    void returnableCountsAreHonest() {
        Fixture f = delivered();
        returnService.request(f.customer().getId(), f.order().getId(),
                Map.of(f.cheap().getId(), 1), null);

        Map<Long, Integer> left = returnService.returnableLines(f.customer().getId(), f.order().getId());
        assertEquals(2, left.get(f.cheap().getId()), "3 bought, 1 asked for.");
        assertEquals(2, left.get(f.dear().getId()), "untouched.");
    }

    @Test
    @DisplayName("an empty request is refused before anything is written")
    void emptyRequestRefused() {
        Fixture f = delivered();
        assertThrows(BadRequestException.class, () -> returnService.request(
                f.customer().getId(), f.order().getId(), Map.of(), null));
        assertTrue(returnRepository.forOrder(f.order().getId()).isEmpty());
    }

    // ------------------------------------------------------------- fixtures

    private void stubRefund(Payment payment, GatewayRefund.State state) {
        doAnswer(call -> {
            PaymentGateway.GatewayRefundRequest asked = call.getArgument(0);
            return new GatewayRefund(asked.refundId(), "cf_ret_" + System.nanoTime(),
                    state, asked.amount(), null);
        }).when(gateway).requestRefund(any());
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setFullName("Returns Customer");
        customer.setEmail("ret-" + System.nanoTime() + "@example.com");
        customer.setMobileNumber("9" + String.format("%09d", System.nanoTime() % 1_000_000_000L));
        customer.setPassword("irrelevant");
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setRole(Role.CUSTOMER);
        return customerRepository.save(customer);
    }

    private Fixture delivered() {
        Customer customer = customer();

        Order order = new Order();
        order.setOrderNumber("RET-" + System.nanoTime());
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("350.00"));
        order.setOrderStatus(OrderStatus.DELIVERED);
        order.setOrderDate(LocalDateTime.now().minusDays(1));
        order.setActive(true);
        order = orderRepository.save(order);

        OrderItem cheap = line(order, "Atta", new BigDecimal("50.00"), 3);
        OrderItem dear = line(order, "Ghee", new BigDecimal("100.00"), 2);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.ONLINE);
        payment.setPaymentStatus(PaymentStatus.SUCCESS);
        payment.setProvider(PaymentProvider.CASHFREE);
        payment.setProviderOrderId("GP-" + order.getId() + "-ret");
        payment.setAmount(new BigDecimal("350.00"));
        payment.setCurrency("INR");
        payment.setActive(true);
        payment = paymentRepository.save(payment);

        Delivery delivery = new Delivery();
        delivery.setOrder(order);
        delivery.setDeliveredAt(LocalDateTime.now().minusHours(6));
        deliveryRepository.save(delivery);

        return new Fixture(order, customer, cheap, dear, payment);
    }

    private OrderItem line(Order order, String name, BigDecimal unitPrice, int quantity) {
        // A CATEGORY, because a real product always has one. Without it this
        // fixture leaves category-less rows in the shared test database, and
        // ProductImagePipelineTest - which scans every product looking for
        // missing photographs - NPEs on them. That failure is this fixture's
        // fault, not that test's: it is asserting something true of the
        // shop's actual data.
        Category category = new Category();
        category.setName("Returns " + System.nanoTime());
        category.setActive(true);
        category.setGstRate(new BigDecimal("5"));
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setName(name + "-" + System.nanoTime());
        product.setCategory(category);
        product.setActive(true);
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("SKU-" + System.nanoTime());
        variant.setSellingPrice(unitPrice);
        variant.setMrp(unitPrice);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setActive(true);
        variant = variantRepository.save(variant);

        Inventory inventory = new Inventory();
        inventory.setProductVariant(variant);
        inventory.setStock(100);
        inventoryService.save(inventory);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductVariant(variant);
        item.setQuantity(quantity);
        item.setPrice(unitPrice);
        item.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        item.setActive(true);
        return orderItemRepository.save(item);
    }
}
