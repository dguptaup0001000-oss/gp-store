package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Order;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Phase 16/18: closes the IDOR every order-detail/cancel endpoint has to
 * defend against - a customer supplying someone else's orderId must never
 * see that order, and the failure has to look identical to "this order
 * doesn't exist" (ResourceNotFoundException / 404), not a 403 that would
 * confirm the ID is real. Admins bypass the check entirely, since they
 * legitimately need to view/manage any order.
 *
 * Only OrderRepository and DeliveryRepository are stubbed here - every
 * other OrderService dependency stays an unused Mockito mock, which is
 * fine because both getOwnedOrderDetail and the ownership check in
 * cancelOrder fail fast before touching anything else.
 */
@ExtendWith(MockitoExtension.class)
class OrderOwnershipTest {

    @Mock private OrderRepository repository;
    @Mock private OrderItemRepository orderItemRepositoryUnused;
    @Mock private CustomerService customerServiceUnused;
    @Mock private AddressService addressServiceUnused;
    @Mock private CartItemService cartItemServiceUnused;
    @Mock private InventoryService inventoryServiceUnused;
    @Mock private com.gpstore.repository.PaymentRepository paymentRepositoryUnused;
    @Mock private CouponService couponServiceUnused;
    @Mock private DeliveryEstimateService deliveryEstimateServiceUnused;
    @Mock private DeliveryFeeService deliveryFeeServiceUnused;
    @Mock private com.gpstore.pricing.DeliveryPricingService deliveryPricingServiceUnused;
    @Mock private NotificationService notificationServiceUnused;
    @Mock private AuditLogService auditLogServiceUnused;
    @Mock private InvoiceService invoiceServiceUnused;
    @Mock private TaxService taxServiceUnused;
    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryService deliveryServiceUnused;
    @Mock private com.gpstore.repository.IdempotencyRecordRepository idempotencyRecordRepositoryUnused;
    @Mock private com.gpstore.repository.OutboxEventRepository outboxEventRepositoryUnused;
    @Mock private PaymentService paymentServiceUnused;
    @Mock private com.gpstore.config.AfterCommitExecutor afterCommitExecutorUnused;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManagerUnused;

    private OrderService orderService;

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_CUSTOMER_ID = 2L;
    private static final Long ORDER_ID = 100L;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                repository, orderItemRepositoryUnused, customerServiceUnused, addressServiceUnused,
                cartItemServiceUnused, inventoryServiceUnused, paymentRepositoryUnused, couponServiceUnused,
                deliveryEstimateServiceUnused, deliveryFeeServiceUnused,
                // Unused here for the same reason as the rest: these tests
                // cover cancellation and ownership, never a path that prices
                // a delivery.
                deliveryPricingServiceUnused,
                notificationServiceUnused,
                auditLogServiceUnused, invoiceServiceUnused, taxServiceUnused, deliveryRepository,
                deliveryServiceUnused, idempotencyRecordRepositoryUnused, afterCommitExecutorUnused,
                transactionManagerUnused,
                outboxEventRepositoryUnused,
                paymentServiceUnused,
                // requireIdempotencyKey: these tests cover cancellation and
                // ownership, never the checkout entry point that reads it.
                false);
    }

    private Order orderOwnedBy(Long ownerId) {
        Customer owner = new Customer();
        owner.setId(ownerId);
        Order order = new Order();
        order.setCustomer(owner);
        return order;
    }

    @Test
    void getOwnedOrderDetailRejectsNonOwnerAsIfOrderDidNotExist() {
        when(repository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(orderOwnedBy(OWNER_ID)));

        assertThrows(ResourceNotFoundException.class,
                () -> orderService.getOwnedOrderDetail(ORDER_ID, OTHER_CUSTOMER_ID, false),
                "A non-owner must see 'not found', not confirmation that this order ID is real");
    }

    @Test
    void getOwnedOrderDetailSucceedsForTheActualOwner() {
        when(repository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(orderOwnedBy(OWNER_ID)));
        when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> orderService.getOwnedOrderDetail(ORDER_ID, OWNER_ID, false));
    }

    @Test
    void getOwnedOrderDetailAdminBypassesOwnershipCheck() {
        when(repository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(orderOwnedBy(OWNER_ID)));
        when(deliveryRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> orderService.getOwnedOrderDetail(ORDER_ID, OTHER_CUSTOMER_ID, true),
                "Admin must be able to view any order regardless of who placed it");
    }

    @Test
    void cancelOrderRejectsNonOwnerAsIfOrderDidNotExist() {
        // cancelOrder locks the row via findByIdForUpdate (see
        // OrderRepository's doc comment - the fix for the concurrent
        // double-cancellation race), not the plain findById the read-only
        // getOwnedOrderDetail tests above use.
        when(repository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(orderOwnedBy(OWNER_ID)));

        assertThrows(ResourceNotFoundException.class,
                () -> orderService.cancelOrder(ORDER_ID, OTHER_CUSTOMER_ID, false),
                "A non-owner must not be able to cancel - or even get a different error for - someone else's order");
    }

    @Test
    void customerCannotCancelOncePackingHasStarted() {
        Order order = orderOwnedBy(OWNER_ID);
        order.setOrderStatus(com.gpstore.enums.OrderStatus.PACKING);
        when(repository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        assertThrows(com.gpstore.exception.ConflictException.class,
                () -> orderService.cancelOrder(ORDER_ID, OWNER_ID, false),
                "A customer must not be able to cancel after the shop has started packing");
    }
}
