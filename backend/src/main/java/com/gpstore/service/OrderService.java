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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

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
    private final com.gpstore.repository.IdempotencyRecordRepository idempotencyRecordRepository;
    private final java.util.concurrent.ExecutorService orderSideEffectsExecutor;
    private final com.gpstore.repository.OutboxEventRepository outboxEventRepository;
    private final boolean requireIdempotencyKey;

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
            @org.springframework.context.annotation.Lazy DeliveryService deliveryService,
            com.gpstore.repository.IdempotencyRecordRepository idempotencyRecordRepository,
            java.util.concurrent.ExecutorService orderSideEffectsExecutor,
            com.gpstore.repository.OutboxEventRepository outboxEventRepository,
            @org.springframework.beans.factory.annotation.Value("${orders.require-idempotency-key:true}")
            boolean requireIdempotencyKey) {

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
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.orderSideEffectsExecutor = orderSideEffectsExecutor;
        this.outboxEventRepository = outboxEventRepository;
        this.requireIdempotencyKey = requireIdempotencyKey;
    }

    public Order save(Order order) {
        return repository.save(order);
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
    @Transactional(readOnly = true)
    public com.gpstore.dto.response.CheckoutPreviewResponse previewCheckout(
            Long customerId, Long addressId, String couponCode) {

        Address address = addressService.getOwnedAddress(addressId, customerId);

        Customer customer = customerService.getById(customerId);
        if (customer == null || customer.getCart() == null) {
            throw new BadRequestException("Cart is empty");
        }

        List<CartItem> cartItems = cartItemService.getCartItemsForCheckout(customer.getCart().getId());
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
     *
     * idempotencyKey is REQUIRED by default (orders.require-idempotency-key,
     * default true). A repeat call with the same (customerId, idempotencyKey)
     * pair returns the original order instead of creating a second one -
     * which is what stops a double-tap on Place Order, or a client retry
     * after a network timeout, from creating two real orders for one
     * purchase.
     *
     * It used to be optional, which in practice meant unused: the Flutter
     * app never sent one, so real checkout had no duplicate protection at
     * all despite the mechanism existing. Accepting a missing key silently
     * is the failure mode that hides that, so order creation now rejects it.
     *
     * The flag exists so the requirement can be turned off for one deploy if
     * an older client build is still in the wild, not as a permanent option
     * - leaving it off returns checkout to having no retry protection.
     */
    @Transactional
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request, Long customerId, String idempotencyKey) {

        if (request == null) {
            throw new BadRequestException("Request cannot be null");
        }

        if (requireIdempotencyKey && (idempotencyKey == null || idempotencyKey.isBlank())) {
            throw new BadRequestException(
                    "Idempotency-Key header is required for placing an order. "
                            + "Generate one UUID per checkout attempt and re-send the SAME value on any retry.");
        }

        // Idempotency check. The unique DB constraint on
        // (customer_id, idempotency_key) - not this lookup alone - is what
        // actually makes this race-safe: if two requests carrying the same key
        // arrive at the same instant, both may pass this findBy... check before
        // either inserts, but only one of the two inserts below can succeed.
        // The loser reports "already processing" rather than silently creating
        // a duplicate order.
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        com.gpstore.entity.IdempotencyRecord idempotencyRecord = null;

        String fingerprint = hasIdempotencyKey ? computeRequestFingerprint(request, customerId) : null;

        if (hasIdempotencyKey) {
            Optional<com.gpstore.entity.IdempotencyRecord> existing =
                    idempotencyRecordRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);

            if (existing.isPresent()) {
                com.gpstore.entity.IdempotencyRecord record = existing.get();

                // Same key, DIFFERENT request = the client reused a key for a
                // genuinely different checkout. Replaying the first order here
                // would mean the customer never receives this second one and
                // has no way to tell, so this is a hard 409 instead.
                //
                // Deliberately skipped when this request's cart is empty. A
                // successful checkout clears the cart, so a legitimate retry
                // of an already-completed attempt necessarily arrives with an
                // empty cart and would otherwise fingerprint differently from
                // the request that created the record - false-409ing exactly
                // the retry this whole mechanism exists to serve. An empty
                // cart can never be a new logical checkout anyway (checkout
                // rejects it outright below), so treating it as "unverifiable,
                // fall through to replay" is safe.
                //
                // Also skipped when the stored fingerprint is null: rows
                // written before this column existed cannot be compared, and
                // failing in-flight keys across a deploy would break real
                // customers mid-checkout.
                if (record.getRequestFingerprint() != null
                        && !record.getRequestFingerprint().equals(fingerprint)
                        && !cartIsEmpty(customerId)) {
                    throw new ConflictException(
                            "This Idempotency-Key was already used for a different order. "
                                    + "Use a new key for a new checkout.");
                }

                if (record.getOrderId() != null) {
                    // Genuine replay: same checkout attempt, already completed.
                    // Return the original result rather than processing again.
                    return buildReplayResponse(record.getOrderId());
                }
                // A record exists with no order yet - a duplicate is either
                // still being processed right now, or a prior attempt crashed
                // before completing (which should have rolled this row back
                // too, since it's inserted inside the same transaction as the
                // rest of checkout - fail safe rather than risk a double order
                // if that assumption is ever wrong).
                throw new ConflictException(
                        "This order is already being processed. Please wait a moment and check your order history before trying again.");
            }

            idempotencyRecord = new com.gpstore.entity.IdempotencyRecord();
            idempotencyRecord.setCustomerId(customerId);
            idempotencyRecord.setIdempotencyKey(idempotencyKey);
            idempotencyRecord.setCreatedAt(LocalDateTime.now());
            idempotencyRecord.setRequestFingerprint(fingerprint);
            try {
                idempotencyRecord = idempotencyRecordRepository.saveAndFlush(idempotencyRecord);
            } catch (org.springframework.dao.DataIntegrityViolationException raceLost) {
                // Another request with the same key won the race and inserted
                // first. Report the same "already processing" message rather
                // than proceeding - we must not create a second order here.
                throw new ConflictException(
                        "This order is already being processed. Please wait a moment and check your order history before trying again.");
            }
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

        List<CartItem> cartItems = cartItemService.getCartItemsForCheckout(cartId);

        if (cartItems == null || cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // Deadlock prevention: always acquire inventory locks in the same
        // global order (ascending variant ID), regardless of the order items
        // happened to be added to this particular cart. Without this, two
        // customers checking out overlapping products in opposite cart order
        // (A: product1 then product2; B: product2 then product1) can each hold
        // one lock while waiting on the other's - a classic deadlock. Sorting
        // here means every checkout requests locks in the same order, so that
        // situation can't occur. Reassigning cartItems (rather than a copy) is
        // safe - every later use in this method is positional/aggregate, none
        // depend on original cart insertion order.
        cartItems = cartItems.stream()
                .sorted(java.util.Comparator.comparing(ci -> ci.getProductVariant().getId()))
                .collect(java.util.stream.Collectors.toList());

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

        order.setOrderNumber(OrderNumberGenerator.generate(repository.nextOrderNumberSequenceValue()));
        order.setCustomer(customer);
        order.setAddress(address);
        order.setOrderDate(LocalDateTime.now());

        if (request.getPaymentMethod().equalsIgnoreCase("COD")) {
            order.setPaymentStatus(PaymentStatus.COD_PENDING);
            // COD has no separate payment-confirmation step to wait for -
            // cash isn't collected until delivery, so gating packing on
            // PENDING_CONFIRMATION here would just mean every COD order
            // (the majority of them) sits idle until an admin manually
            // clicks "Confirmed" for no real reason. UPI orders still start
            // PENDING_CONFIRMATION and only advance once payment actually
            // succeeds - see PaymentService.advanceOrderIfStillPending -
            // so an unpaid/abandoned UPI order never gets packed.
            order.setOrderStatus(OrderStatus.CONFIRMED);
        } else {
            order.setPaymentStatus(PaymentStatus.PENDING);
            order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
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

        if (idempotencyRecord != null) {
            // Marks this key as completed, pointing at the real order - any
            // repeat request with the same key from here on replays this
            // result instead of running checkout again.
            idempotencyRecord.setOrderId(order.getId());
            idempotencyRecordRepository.save(idempotencyRecord);
        }

        List<OrderItem> newOrderItems = new ArrayList<>();

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

            newOrderItems.add(orderItem);
        }
        // One batched round-trip instead of one INSERT per cart item (see
        // hibernate.jdbc.batch_size / order_inserts in application.properties -
        // without those, saveAll alone doesn't actually batch anything, it
        // just still issues N individual statements in a loop under the hood).
        orderItemRepository.saveAll(newOrderItems);

        // Reduce inventory using the rows we already locked above.
        //
        // No save() call: these Inventory instances were loaded by
        // findByProductVariantIdForUpdate inside THIS transaction, so they
        // are managed entities and Hibernate's dirty checking writes the new
        // stock at flush. Calling save() on an already-managed entity adds a
        // merge and a validation pass per item without changing what reaches
        // the database, and it obscures that the row is already locked and
        // owned by this transaction.
        //
        // The lock is untouched and must stay: the row was selected FOR
        // UPDATE above, which is what makes read-modify-write safe here.
        // Dropping save() changes when the UPDATE is issued, never whether
        // the row is protected.
        for (int i = 0; i < cartItems.size(); i++) {
            Inventory inventory = lockedInventories.get(i);
            CartItem item = cartItems.get(i);
            int newStock = inventory.getStock() - item.getQuantity();
            if (newStock < 0) {
                // Defensive: the availability check above should already have
                // rejected this. Kept because silently persisting negative
                // stock is far worse than an explicit failure.
                throw new ConflictException("Insufficient stock while placing the order - please try again.");
            }
            inventory.setStock(newStock);
        }

        // Clear cart
        cartItemService.clearCart(cartId);

        // Durable record of the post-order work that must not be lost.
        // INSERTed inside THIS transaction on purpose: it commits with the
        // order or not at all, so there is no window in which an order
        // exists but its invoice work was never recorded. Contrast the
        // executor below, which is in-memory and does not survive the
        // redeploys this service gets on every push.
        outboxEventRepository.save(com.gpstore.entity.OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, order.getId(), OutboxWorker.EVENT_ORDER_PLACED));

        // None of notification creation, the audit log entry, invoice
        // generation, or delivery auto-assignment need to block the
        // customer's "order placed" response, or run inside the same
        // transaction as the order/inventory writes above - keeping them in
        // this transaction only meant a slow invoice/notification write held
        // the inventory row locks taken earlier in this method for longer
        // than necessary. Deferred as one after-commit callback instead.
        //
        // Also fixes the same visibility problem documented on
        // DeliveryService.autoAssignBestEffort (REQUIRES_NEW/its own
        // transactions could otherwise run before this order's INSERT
        // commits): invoiceService.generateForOrder re-reads the order by ID
        // in its own transaction, so it must not run until that INSERT is
        // durably committed and visible either.
        //
        // order.getCustomer() is safe to read here despite running after
        // this transaction/session closes - it was assigned above from
        // customerService.getById(customerId), a fully-loaded Customer, not
        // a lazy proxy, so no further DB access is needed to read it.
        final Long placedOrderId = order.getId();
        final Order placedOrder = order;
        Runnable afterCommitWork = () -> {
            // notifyOrderStatusChange and autoAssignBestEffort already catch
            // every exception internally (see their own doc comments) - the
            // order is already committed by this point, so nothing here may
            // throw out of this callback. An uncaught exception from a
            // TransactionSynchronization.afterCommit() propagates straight
            // out of the transactional placeOrder() call, which would turn
            // an already-successful order into an error response to the
            // customer. generateForOrder has no such internal guard, so it
            // gets one here, same pattern as the other three.
            notificationService.notifyOrderStatusChange(placedOrder, placedOrder.getOrderStatus());
            notificationService.notifyAdminsOfNewOrder(placedOrder);
            auditLogService.log("ORDER_PLACED", "Order", placedOrderId,
                    "total=" + placedOrder.getTotalAmount() + ", paymentMethod=" + request.getPaymentMethod());
            // Invoice generation and delivery assignment deliberately do
            // NOT run here any more. They are durable outbox work now (see
            // the outbox insert above and OutboxWorker): losing an invoice
            // because the process was redeployed mid-flight is a real
            // accounting problem, and this executor - correctly bounded as
            // it is - cannot survive a restart. What stays here is the
            // genuinely best-effort, time-sensitive part: a push
            // notification is worth little if delivered minutes late by a
            // background worker, and losing one is acceptable.
        };

        // Belt-and-suspenders on top of the comment above: every individual
        // call inside afterCommitWork is SUPPOSED to catch its own
        // exceptions, but that guarantee lives in four different methods
        // across three services, maintained separately over time - one
        // regression, or one exception type narrower than what a future
        // change can throw (an Error subtype slipping past a `catch
        // (Exception ...)`, for instance), and this whole safety net breaks
        // silently. Catching Throwable here directly enforces the actual
        // invariant this code depends on - nothing from this point on may
        // turn an already-committed, already-successful order into an error
        // response - rather than just hoping every callee upholds it
        // forever. If something still gets here, at least it's on record
        // instead of surfacing to the customer as "An unexpected error
        // occurred" for an order that, in reality, already went through.
        //
        // Submitted to orderSideEffectsExecutor, not run inline:
        // TransactionSynchronization.afterCommit() still executes
        // synchronously on the SAME request thread, just after the DB
        // commit rather than before it - registering it here alone did NOT
        // make the FCM push call, invoice generation, and delivery-partner
        // query stop blocking the customer's response (measured at several
        // real seconds of placeOrder()'s latency in production). Handing
        // the Runnable to the executor is what actually gets it off the
        // request thread.
        Runnable guardedAfterCommitWork = () -> {
            try {
                afterCommitWork.run();
            } catch (Throwable t) {
                log.error("Post-order side effects failed for order {} - order itself is unaffected", placedOrderId, t);
            }
        };
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                orderSideEffectsExecutor.submit(guardedAfterCommitWork);
                            }
                        });
            } else {
                // Defensive fallback only - shouldn't happen inside an @Transactional
                // method, but never silently drop these side effects if it does.
                orderSideEffectsExecutor.submit(guardedAfterCommitWork);
            }
        } catch (Throwable t) {
            log.error("Failed to register/run post-order side effects for order {} - order itself is unaffected", placedOrderId, t);
        }

        PlaceOrderResponse response = new PlaceOrderResponse();

        response.setSuccess(true);
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setMessage("Order placed successfully.");

        return response;
    }

    /** Paginated - a repeat customer's order history has no natural upper bound over the years. */
    public org.springframework.data.domain.Page<OrderResponse> getMyOrders(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return repository.findByCustomerIdOrderByOrderDateDesc(customerId, pageable)
                .map(order -> toOrderResponse(order, false));
    }

    /**
     * Every order in the system, newest first, WITH customer name (the raw
     * entity alone can't show this - Order.customer is hidden from JSON).
     * Paginated at the DB level (ORDER BY + LIMIT/OFFSET in SQL) instead of
     * loading every order row into Java just to sort a handful into view.
     */
    public org.springframework.data.domain.Page<OrderResponse> getAllOrdersForAdmin(
            org.springframework.data.domain.Pageable pageable) {
        return repository.findAllByOrderByOrderDateDesc(pageable)
                .map(order -> toOrderResponse(order, true));
    }

    /**
     * A specific customer's order history, admin-only - for support/dispute
     * lookups ("this customer says their order never arrived"). Was an
     * unbounded findByCustomerId() loading every order this customer has
     * ever placed into Java just to sort a handful into view - a customer
     * with a long order history would load their entire history on every
     * lookup. Now uses the same paginated, database-sorted query as every
     * other order list in the app (findByCustomerIdOrderByOrderDateDesc,
     * already used by the customer-facing /my-orders endpoint).
     */
    public org.springframework.data.domain.Page<OrderResponse> getCustomerOrdersForAdmin(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return repository.findByCustomerIdOrderByOrderDateDesc(customerId, pageable)
                .map(order -> toOrderResponse(order, false));
    }

    private OrderResponse toOrderResponse(Order order, boolean includeCustomerName) {
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

        return response;
    }

    @Transactional
    public com.gpstore.dto.response.OrderDetailResponse updateOrderStatus(Long orderId, OrderStatus status) {

        // Locked for the rest of this transaction (see
        // OrderRepository.findByIdForUpdate's doc comment) - a second
        // concurrent status update or cancellation for this same order
        // blocks here until this one commits, instead of both reading the
        // same pre-change status and both applying conflicting changes.
        Order order = repository.findByIdForUpdate(orderId)
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

        var delivery = deliveryRepository.findByOrderId(savedOrder.getId()).orElse(null);
        return com.gpstore.dto.response.OrderDetailResponse.from(savedOrder, delivery);
    }

    /**
     * callerCustomerId/isAdmin enforce that a customer can only cancel their
     * own order, while staff can cancel any order.
     */
    @Transactional
    public com.gpstore.dto.response.OrderDetailResponse cancelOrder(Long orderId, Long callerCustomerId, boolean isAdmin) {

        // See OrderRepository.findByIdForUpdate's doc comment - this is the
        // fix for the exact race a double-tap on "Cancel order" (or two
        // concurrent cancellation requests) could otherwise trigger: both
        // reading CONFIRMED, both passing the not-yet-cancelled check below,
        // and both restoring inventory for the same order.
        Order order = repository.findByIdForUpdate(orderId)
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

        // ORDER (already locked above) -> PAYMENT -> INVENTORY. Locking the
        // payment row rather than plain-reading it: the expiry sweep and the
        // UPI/COD confirmation paths can be looking at this same payment
        // right now, and its status has to be re-read under the lock before
        // being acted on rather than trusted from an unlocked read.
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);

        if (payment != null) {

            if (payment.getPaymentMethod() == PaymentMethod.COD
                    && payment.getPaymentStatus() == PaymentStatus.COD_PENDING) {

                payment.setPaymentStatus(PaymentStatus.FAILED);

            } else if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {

                payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);

            } else if (payment.getPaymentStatus() == PaymentStatus.PENDING) {

                // Previously fell through untouched, which is what let a
                // cancelled order keep a PENDING UPI payment carrying an old
                // payment_date - still matching the stale-payment sweep's
                // query, which then restored this order's stock a second
                // time. A cancelled order's unconfirmed payment can never
                // legitimately succeed later, so it is terminal now.
                payment.setPaymentStatus(PaymentStatus.FAILED);
            }
            paymentRepository.save(payment);
        }

        // Give back the stock that was reserved for this order at checkout
        // time. Guarded so it happens exactly once across every path that
        // can trigger it - see restoreInventoryOnce.
        restoreInventoryOnce(order);

        Order savedOrder = repository.save(order);
        notificationService.notifyOrderStatusChange(savedOrder, savedOrder.getOrderStatus());

        // This was never triggered anywhere before - a cancelled order's
        // invoice would stay in whatever state it was, still implying a
        // valid sale for GST/accounting purposes even though the order
        // itself no longer represents one.
        invoiceService.getInvoiceByOrderId(orderId).ifPresent(invoice -> invoiceService.cancelInvoice(invoice.getInvoiceId()));

        auditLogService.log("ORDER_CANCELLED", "Order", savedOrder.getId(),
                "cancelled by " + (isAdmin ? "admin/staff" : "customer"));

        var delivery = deliveryRepository.findByOrderId(savedOrder.getId()).orElse(null);
        return com.gpstore.dto.response.OrderDetailResponse.from(savedOrder, delivery);
    }

    /**
     * Deterministic fingerprint of "which checkout is this", used to tell a
     * retried request apart from a reused key (see the idempotency block in
     * placeOrder).
     *
     * CANONICAL FIELDS - exactly these, in this order:
     *
     *   1. customerId      - scopes the hash to one account, so two
     *                        customers' identical carts never collide.
     *   2. addressId       - delivering the same basket somewhere else is a
     *                        different order.
     *   3. paymentMethod   - upper-cased; "cod" and "COD" are the same
     *                        request, not two.
     *   4. couponCode      - upper-cased, null and blank both normalise to
     *                        empty, so "no coupon" has one representation.
     *   5. cart line items - each as variantId:quantity, SORTED by variant
     *                        id. Sorted because cart iteration order is not
     *                        guaranteed and must not change the hash; the
     *                        same basket has to fingerprint identically
     *                        every time.
     *
     * Cart contents are included on purpose: the request body alone carries
     * no basket, so without them "same key, different quantity" and "same
     * key, different product" would be indistinguishable from a retry.
     *
     * NOT included, deliberately: nothing sensitive and nothing unstable.
     * No prices (server-derived, and a price change between attempts must
     * not turn a retry into a conflict), no timestamps, no request ids, no
     * customer name/phone/address text - the address is referenced by id
     * rather than hashing where somebody lives.
     *
     * SHA-256 because it is collision-resistant and fixed-width, so the
     * column stays 64 chars regardless of basket size. This is a
     * change-detector, not a security control - it protects against client
     * mistakes, not against a caller deliberately forging a matching hash,
     * who could equally just send the original request.
     */
    private String computeRequestFingerprint(PlaceOrderRequest request, Long customerId) {
        StringBuilder canonical = new StringBuilder()
                .append(customerId).append('|')
                .append(request.getAddressId()).append('|')
                .append(request.getPaymentMethod() == null
                        ? "" : request.getPaymentMethod().trim().toUpperCase()).append('|')
                .append(request.getCouponCode() == null
                        ? "" : request.getCouponCode().trim().toUpperCase()).append('|');

        Customer customer = customerService.getById(customerId);
        if (customer != null && customer.getCart() != null) {
            List<CartItem> items = cartItemService.getCartItemsForCheckout(customer.getCart().getId());
            if (items != null) {
                items.stream()
                        .filter(item -> item.getProductVariant() != null)
                        .sorted(java.util.Comparator.comparing(item -> item.getProductVariant().getId()))
                        .forEach(item -> canonical
                                .append(item.getProductVariant().getId())
                                .append(':')
                                .append(item.getQuantity())
                                .append(','));
            }
        }

        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM - unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * Whether this customer's cart currently has nothing in it. Used only to
     * decide whether a fingerprint mismatch is meaningful - see the
     * idempotency block in placeOrder for why an empty cart makes the
     * comparison unverifiable rather than conflicting.
     */
    private boolean cartIsEmpty(Long customerId) {
        Customer customer = customerService.getById(customerId);
        if (customer == null || customer.getCart() == null) {
            return true;
        }
        List<CartItem> items = cartItemService.getCartItemsForCheckout(customer.getCart().getId());
        return items == null || items.isEmpty();
    }

    /**
     * Rebuilds the same PlaceOrderResponse a completed checkout would have
     * returned, for a repeat request carrying an Idempotency-Key that's
     * already been fulfilled. Deliberately does not touch inventory, coupons,
     * or the cart again - none of that should run a second time for the same
     * checkout attempt.
     */
    private PlaceOrderResponse buildReplayResponse(Long orderId) {
        Order order = repository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for a completed idempotency record - data inconsistency, contact support"));

        PlaceOrderResponse response = new PlaceOrderResponse();
        response.setSuccess(true);
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setMessage("Order already placed successfully.");
        return response;
    }

    /**
     * Entry point for any caller that is NOT already holding this order's
     * row lock - currently the stale-UPI expiry sweep. Takes the order lock
     * first, then delegates, keeping the project-wide ORDER -> PAYMENT ->
     * INVENTORY ordering intact.
     *
     * @return true if this call is the one that actually restored the stock,
     *         false if some other path had already done it. Callers use the
     *         return value to decide whether to log/audit a restore, so the
     *         audit trail records one restore per order rather than one per
     *         attempt.
     */
    @Transactional
    public boolean restoreInventoryForOrder(Long orderId) {
        Order order = repository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        boolean restored = restoreInventoryOnce(order);
        if (restored) {
            repository.save(order);
        }
        return restored;
    }

    /**
     * Adds back every line item's quantity to its variant's stock count -
     * the exact inverse of the decrement placeOrder() does at checkout -
     * but only if no other path has already done so for this order.
     *
     * The caller MUST already hold this order's row lock (cancelOrder gets
     * it from findByIdForUpdate; restoreInventoryForOrder takes it itself).
     * That lock is what makes the check-and-set below atomic: two paths
     * racing to restore the same order serialize on the order row, the
     * first sets the flag, and the second sees it set and does nothing.
     *
     * Reading and writing order status instead would not be enough - the
     * paths disagree about what status means. The expiry sweep restores
     * stock for orders that were never cancelled at all, so "is it
     * CANCELLED" cannot be the guard; "has the stock gone back" has to be
     * tracked as its own fact.
     *
     * Inventory rows are locked individually and in the order the items
     * come back in, matching what placeOrder() does, so a restore racing a
     * fresh checkout on the same variant cannot interleave into a wrong
     * count.
     */
    private boolean restoreInventoryOnce(Order order) {
        if (Boolean.TRUE.equals(order.getInventoryRestored())) {
            return false;
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        for (OrderItem item : items) {
            if (item.getProductVariant() == null) {
                continue;
            }
            Inventory inventory = inventoryService.getByProductVariantForUpdate(item.getProductVariant().getId());
            if (inventory == null || item.getQuantity() == null) {
                continue;
            }
            inventory.setStock(inventory.getStock() + item.getQuantity());
            inventoryService.save(inventory);
        }

        // Set even when the order had no restorable items: the question this
        // answers is "has the restore step run for this order", and running
        // it again for a zero-item order is still pointless work.
        order.setInventoryRestored(true);
        return true;
    }
}
