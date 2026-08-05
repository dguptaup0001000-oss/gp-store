package com.gpstore.service;

import com.gpstore.dto.OrderResponse;
import com.gpstore.dto.request.PlaceOrderRequest;
import com.gpstore.dto.response.PlaceOrderResponse;

import java.math.BigDecimal;
import com.gpstore.entity.Address;
import com.gpstore.entity.CartItem;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Inventory;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Payment;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.PaymentRepository;

import com.gpstore.enums.OrderStatus;

import com.gpstore.repository.OrderItemRepository;
import com.gpstore.repository.OrderRepository;
import com.gpstore.util.OrderNumberGenerator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerService customerService;
    private final AddressService addressService;
    private final CartItemService cartItemService;
    private final InventoryService inventoryService;
    private final PaymentRepository paymentRepository;
    private final CouponService couponService;
    private final DeliveryEstimateService deliveryEstimateService;
    private final DeliveryFeeService deliveryFeeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final InvoiceService invoiceService;
    private final TaxService taxService;
    private final com.gpstore.repository.DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;

    public OrderService(
            OrderRepository repository,
            OrderItemRepository orderItemRepository,
            CustomerService customerService,
            AddressService addressService,
            CartItemService cartItemService,
            InventoryService inventoryService,
            PaymentRepository paymentRepository,
            CouponService couponService,
            DeliveryEstimateService deliveryEstimateService,
            DeliveryFeeService deliveryFeeService,
            NotificationService notificationService,
            AuditLogService auditLogService,
            InvoiceService invoiceService,
            TaxService taxService,
            com.gpstore.repository.DeliveryRepository deliveryRepository,
            // @Lazy breaks a real circular dependency: DeliveryService now
            // depends on PaymentService (added for the COD-completion fix),
            // and PaymentService already depended on OrderService - without
            // @Lazy here, Spring would fail to start the whole application
            // at boot, not just this feature.
            @org.springframework.context.annotation.Lazy DeliveryService deliveryService) {

        this.repository = repository;
        this.orderItemRepository = orderItemRepository;
        this.customerService = customerService;
        this.addressService = addressService;
        this.cartItemService = cartItemService;
        this.inventoryService = inventoryService;
        this.paymentRepository = paymentRepository;
        this.couponService = couponService;
        this.deliveryEstimateService = deliveryEstimateService;
        this.deliveryFeeService = deliveryFeeService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.invoiceService = invoiceService;
        this.taxService = taxService;
        this.deliveryRepository = deliveryRepository;
        this.deliveryService = deliveryService;
    }

    public Order save(Order order) {
        return repository.save(order);
    }

    public List<Order> getAll() {
        return repository.findAll();
    }

    /** Ownership-checked single order lookup with real items/tracking info - see OrderDetailResponse. */
    /**
     * isAdmin bypasses the ownership check entirely - same pattern already
     * used for cancelOrder and updateDeliveryStatus. A customer calling this
     * still only ever sees their own order; an admin can view any order to
     * actually fulfill/manage it.
     */
    public com.gpstore.dto.response.OrderDetailResponse getOwnedOrderDetail(Long orderId, Long customerId, boolean isAdmin) {
        Order order = repository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!isAdmin && (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId))) {
            throw new ResourceNotFoundException("Order not found");
        }

        var delivery = deliveryRepository.findByOrderId(orderId).orElse(null);

        return com.gpstore.dto.response.OrderDetailResponse.from(order, delivery);
    }

    /**
     * Read-only - does NOT redeem the coupon, lock inventory, or create
     * anything. Lets the app show a real cost breakdown (subtotal, discount,
     * delivery fee, estimated total) before the customer commits to placing
     * the order, instead of only finding out the final cost afterward.
     */
    public com.gpstore.dto.response.CheckoutPreviewResponse previewCheckout(
            Long customerId, Long addressId, String couponCode) {

        Address address = addressService.getOwnedAddress(addressId, customerId);

        Customer customer = customerService.getById(customerId);
        if (customer == null || customer.getCart() == null) {
            throw new BadRequestException("Cart is empty");
        }

        List<CartItem> cartItems = cartItemService.getCartItems(customer.getCart().getId());
        if (cartItems == null || cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            // Same check as placeOrder - surfaced here too so the customer
            // sees "this item is no longer available" while still on the
            // checkout preview, not only after tapping Place Order.
            var variant = item.getProductVariant();
            if (variant.getAvailable() == null || !variant.getAvailable()
                    || variant.getProduct() == null
                    || variant.getProduct().getActive() == null
                    || !variant.getProduct().getActive()) {
                throw new ConflictException(
                        (variant.getProduct() != null ? variant.getProduct().getName() : "An item")
                                + " is no longer available - please remove it from your cart.");
            }

            subtotal = subtotal.add(
                    item.getProductVariant().getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        BigDecimal discountAmount = BigDecimal.ZERO;
        String couponError = null;
        if (couponCode != null && !couponCode.isBlank()) {
            try {
                discountAmount = couponService.previewDiscount(couponCode, subtotal);
            } catch (Exception ex) {
                couponError = ex.getMessage();
            }
        }

        boolean deliverable = deliveryEstimateService.isWithinServiceableRadius(
                address.getLatitude(), address.getLongitude());

        BigDecimal deliveryFee = BigDecimal.ZERO;
        boolean freeDeliveryApplied = false;
        Integer estimatedMinutes = null;

        if (deliverable) {
            double distanceKm = deliveryEstimateService.distanceFromStoreKm(address.getLatitude(), address.getLongitude());
            deliveryFee = deliveryFeeService.calculateDeliveryFee(distanceKm);
            BigDecimal grossProfit = deliveryFeeService.calculateGrossProfit(cartItems);
            freeDeliveryApplied = deliveryFeeService.isFreeDeliveryEligible(grossProfit, deliveryFee);
            estimatedMinutes = deliveryEstimateService.estimateMinutes(address.getLatitude(), address.getLongitude());
        }

        BigDecimal effectiveDeliveryFee = freeDeliveryApplied ? BigDecimal.ZERO : deliveryFee;
        BigDecimal estimatedTotal = subtotal.subtract(discountAmount).add(effectiveDeliveryFee);

        return new com.gpstore.dto.response.CheckoutPreviewResponse(
                subtotal, discountAmount, effectiveDeliveryFee, estimatedTotal,
                freeDeliveryApplied, deliverable, estimatedMinutes, couponError);
    }

    /**
     * customerId always comes from the authenticated JWT (see OrderController),
     * never from the request body - this is what closes the IDOR that let any
     * caller place orders as any customer.
     */
    @Transactional
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request, Long customerId) {

        if (request == null) {
            throw new BadRequestException("Request cannot be null");
        }

        Customer customer = customerService.getById(customerId);
        if (customer == null) {
            throw new ResourceNotFoundException("Customer not found");
        }

        // Throws if the address doesn't belong to this customer.
        Address address = addressService.getOwnedAddress(request.getAddressId(), customerId);

        // Business rule: one store, not a dark-store network - don't accept an
        // order we can't realistically deliver. Fails closed if the address has
        // no coordinates at all, rather than silently accepting an unverified order.
        if (!deliveryEstimateService.isWithinServiceableRadius(address.getLatitude(), address.getLongitude())) {
            throw new BadRequestException(
                    "This address is outside our delivery range (" +
                            deliveryEstimateService.getMaxDeliveryRadiusKm() + " km). " +
                            "Please choose a different address or add location coordinates to this one.");
        }

        if (customer.getCart() == null) {
            throw new BadRequestException("Customer cart not found");
        }

        Long cartId = customer.getCart().getId();

        List<CartItem> cartItems = cartItemService.getCartItems(cartId);

        if (cartItems == null || cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // Inventory validation + row lock. Locking here (and holding the lock for
        // the rest of this transaction) is what prevents two concurrent checkouts
        // from both passing the stock check and overselling the same item.
        List<Inventory> lockedInventories = new ArrayList<>();

        for (CartItem item : cartItems) {

            // A product/variant that's been deactivated since it was added
            // to this cart must not be purchasable, even if inventory still
            // shows stock - "deactivated" means the store has stopped
            // selling it, which is a separate concept from stock count and
            // was never actually checked here before this.
            var variant = item.getProductVariant();
            if (variant.getAvailable() == null || !variant.getAvailable()
                    || variant.getProduct() == null
                    || variant.getProduct().getActive() == null
                    || !variant.getProduct().getActive()) {
                throw new ConflictException(
                        (variant.getProduct() != null ? variant.getProduct().getName() : "An item")
                                + " is no longer available - please remove it from your cart.");
            }

            Inventory inventory = inventoryService
                    .getByProductVariantForUpdate(item.getProductVariant().getId());

            if (inventory == null) {
                throw new ResourceNotFoundException(
                        "Inventory not found for Product Variant ID: "
                                + item.getProductVariant().getId());
            }

            if (inventory.getStock() < item.getQuantity()) {
                throw new ConflictException(
                        item.getProductVariant().getSku()
                                + " has only "
                                + inventory.getStock()
                                + " item(s) available.");
            }

            lockedInventories.add(inventory);
        }

        Order order = new Order();

        String datePrefix = "GP" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String maxOrderNumber = repository.findMaxOrderNumberWithPrefix(datePrefix);
        int nextSeq = 1;
        if (maxOrderNumber != null && maxOrderNumber.length() >= datePrefix.length() + 4) {
            nextSeq = Integer.parseInt(maxOrderNumber.substring(datePrefix.length())) + 1;
        }
        order.setOrderNumber(datePrefix + String.format("%04d", nextSeq));
        order.setCustomer(customer);
        order.setAddress(address);
        order.setOrderDate(LocalDateTime.now());

        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);

        if (request.getPaymentMethod().equalsIgnoreCase("COD")) {
            order.setPaymentStatus(PaymentStatus.COD_PENDING);
        } else {
            order.setPaymentStatus(PaymentStatus.PENDING);
        }

        order.setActive(true);

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CartItem item : cartItems) {
            totalAmount = totalAmount.add(
                    item.getProductVariant()
                            .getSellingPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // Coupon is validated and redeemed (usage count incremented, under a row
        // lock) here, inside the same transaction as the rest of checkout - so a
        // failure anywhere else in placeOrder rolls the redemption back too.
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            discountAmount = couponService.redeem(request.getCouponCode(), totalAmount);
            order.setAppliedCouponCode(request.getCouponCode().toUpperCase());
            order.setDiscountAmount(discountAmount);
        }

        order.setTotalAmount(totalAmount.subtract(discountAmount));

        // Delivery fee: distance-based, with a profit-gated free-delivery rule.
        // We already know the address has valid coordinates - the radius check
        // above would have thrown otherwise.
        double distanceKm = deliveryEstimateService.distanceFromStoreKm(address.getLatitude(), address.getLongitude());
        BigDecimal deliveryFee = deliveryFeeService.calculateDeliveryFee(distanceKm);
        BigDecimal grossProfit = deliveryFeeService.calculateGrossProfit(cartItems);
        boolean freeDeliveryApplied = deliveryFeeService.isFreeDeliveryEligible(grossProfit, deliveryFee);

        order.setDeliveryFee(freeDeliveryApplied ? BigDecimal.ZERO : deliveryFee);
        order.setFreeDeliveryApplied(freeDeliveryApplied);

        if (!freeDeliveryApplied) {
            order.setTotalAmount(order.getTotalAmount().add(deliveryFee));
        }

        order = repository.save(order);

        for (CartItem item : cartItems) {

            OrderItem orderItem = new OrderItem();

            orderItem.setOrder(order);
            orderItem.setProductVariant(item.getProductVariant());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setPrice(item.getProductVariant().getSellingPrice());
            orderItem.setTotalPrice(item.getProductVariant().getSellingPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity())));
            orderItem.setGstRate(taxService.resolveGstRate(item.getProductVariant()));
            orderItem.setActive(true);

            orderItemRepository.save(orderItem);
        }

        // Reduce inventory using the rows we already locked above.
        for (int i = 0; i < cartItems.size(); i++) {
            Inventory inventory = lockedInventories.get(i);
            CartItem item = cartItems.get(i);
            inventory.setStock(inventory.getStock() - item.getQuantity());
            inventoryService.save(inventory);
        }

        // Clear cart
        cartItemService.clearCart(cartId);

        notificationService.notifyOrderStatusChange(order, order.getOrderStatus());
        auditLogService.log("ORDER_PLACED", "Order", order.getId(),
                "total=" + order.getTotalAmount() + ", paymentMethod=" + request.getPaymentMethod());
        invoiceService.generateForOrder(order.getId());

        // This was never triggered anywhere in the entire application before
        // this - meaning NO order, ever, would actually get assigned to a
        // delivery partner unless someone manually called the assign
        // endpoint directly via the API. Best-effort and fully isolated
        // (see DeliveryService.autoAssignBestEffort's doc comment) - if no
        // delivery partner happens to be available right now, the order
        // still succeeds; it just needs manual assignment afterward.
        deliveryService.autoAssignBestEffort(order.getId());

        PlaceOrderResponse response = new PlaceOrderResponse();

        response.setSuccess(true);
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setMessage("Order placed successfully.");

        return response;
    }

    public List<OrderResponse> getMyOrders(Long customerId) {
        List<Order> orders = repository.findByCustomerIdOrderByOrderDateDesc(customerId);
        return toOrderResponseList(orders, false);
    }

    /** Every order in the system, newest first, WITH customer name - the raw entity alone can't show this (Order.customer is hidden from JSON). */
    public List<OrderResponse> getAllOrdersForAdmin() {
        List<Order> orders = repository.findAll();
        orders.sort((a, b) -> b.getOrderDate().compareTo(a.getOrderDate()));
        return toOrderResponseList(orders, true);
    }

    /**
     * A specific customer's order history, admin-only - for support/dispute
     * lookups ("this customer says their order never arrived"). Previously
     * returned a raw, unsorted List<Order> that no Flutter screen ever
     * actually called - now uses the same clean DTO and newest-first
     * ordering as every other order list in the app.
     */
    public List<OrderResponse> getCustomerOrdersForAdmin(Long customerId) {
        List<Order> orders = repository.findByCustomerId(customerId);
        orders.sort((a, b) -> b.getOrderDate().compareTo(a.getOrderDate()));
        return toOrderResponseList(orders, false);
    }

    /** Shared by all three list methods above - kept as one method so they can never silently drift apart in shape. */
    private List<OrderResponse> toOrderResponseList(List<Order> orders, boolean includeCustomerName) {
        List<OrderResponse> responseList = new ArrayList<>();

        for (Order order : orders) {
            OrderResponse response = new OrderResponse();

            response.setOrderId(order.getId());
            response.setOrderNumber(order.getOrderNumber());
            response.setTotalAmount(order.getTotalAmount());
            response.setOrderStatus(order.getOrderStatus().name());
            response.setPaymentStatus(order.getPaymentStatus().name());
            response.setOrderDate(order.getOrderDate());

            if (includeCustomerName) {
                response.setCustomerName(order.getCustomer() != null ? order.getCustomer().getFullName() : null);
            }

            responseList.add(response);
        }

        return responseList;
    }

    @Transactional
    public Order updateOrderStatus(Long orderId, OrderStatus status) {

        Order order = repository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getOrderStatus() == OrderStatus.DELIVERED) {
            throw new ConflictException("Delivered order status cannot be changed");
        }

        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("Cancelled order status cannot be changed");
        }

        OrderStatus currentStatus = order.getOrderStatus();

        boolean validTransition =
                (currentStatus == OrderStatus.PENDING_CONFIRMATION && status == OrderStatus.CONFIRMED)
                        || (currentStatus == OrderStatus.CONFIRMED && status == OrderStatus.PACKING)
                        || (currentStatus == OrderStatus.PACKING && status == OrderStatus.READY_TO_DISPATCH)
                        || (currentStatus == OrderStatus.READY_TO_DISPATCH && status == OrderStatus.OUT_FOR_DELIVERY)
                        || (currentStatus == OrderStatus.OUT_FOR_DELIVERY && status == OrderStatus.DELIVERED);

        if (!validTransition) {
            throw new ConflictException(
                    "Invalid order status transition from " + currentStatus + " to " + status);
        }
        order.setOrderStatus(status);
        if (status == OrderStatus.OUT_FOR_DELIVERY) { order.setDispatchedAt(LocalDateTime.now()); }

        if (status == OrderStatus.DELIVERED) {

            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);

            if (payment != null
                    && payment.getPaymentMethod() == PaymentMethod.COD
                    && payment.getPaymentStatus() == PaymentStatus.COD_PENDING) {

                payment.setPaymentStatus(PaymentStatus.COD_RECEIVED);
                payment.setPaymentDate(LocalDateTime.now());

                paymentRepository.save(payment);
            }
        }

        Order savedOrder = repository.save(order);
        notificationService.notifyOrderStatusChange(savedOrder, savedOrder.getOrderStatus());
        auditLogService.log("ORDER_STATUS_CHANGED", "Order", savedOrder.getId(),
                "status: " + currentStatus + " -> " + status);
        return savedOrder;
    }

    /**
     * callerCustomerId/isAdmin enforce that a customer can only cancel their
     * own order, while staff can cancel any order.
     */
    @Transactional
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    public void autoConfirmStaleOrders() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusSeconds(60);
        java.util.List<Order> staleOrders = repository.findByOrderStatusAndOrderDateBefore(OrderStatus.PENDING_CONFIRMATION, cutoff);
        for (Order order : staleOrders) {
            try {
                updateOrderStatus(order.getId(), OrderStatus.CONFIRMED);
            } catch (Exception ex) {
                // Skip this order and continue the sweep - one bad order should not block others.
            }
        }
    }

    public Order cancelOrder(Long orderId, Long callerCustomerId, boolean isAdmin) {

        Order order = repository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!isAdmin && (order.getCustomer() == null
                || !order.getCustomer().getId().equals(callerCustomerId))) {
            throw new ResourceNotFoundException("Order not found");
        }

        if (order.getOrderStatus() == OrderStatus.DELIVERED) {
            throw new ConflictException("Delivered order cannot be cancelled");
        }

        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException("Order is already cancelled");
        }

        order.setOrderStatus(OrderStatus.CANCELLED);

        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);

        if (payment != null) {

            if (payment.getPaymentMethod() == PaymentMethod.COD
                    && payment.getPaymentStatus() == PaymentStatus.COD_PENDING) {

                payment.setPaymentStatus(PaymentStatus.FAILED);

            } else if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {

                payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);
            }
            paymentRepository.save(payment);
        }

        Order savedOrder = repository.save(order);
        notificationService.notifyOrderStatusChange(savedOrder, savedOrder.getOrderStatus());

        // This was never triggered anywhere before - a cancelled order's
        // invoice would stay in whatever state it was, still implying a
        // valid sale for GST/accounting purposes even though the order
        // itself no longer represents one.
        invoiceService.getInvoiceByOrderId(orderId).ifPresent(invoice -> invoiceService.cancelInvoice(invoice.getId()));

        auditLogService.log("ORDER_CANCELLED", "Order", savedOrder.getId(),
                "cancelled by " + (isAdmin ? "admin/staff" : "customer"));
        return savedOrder;
    }
}
