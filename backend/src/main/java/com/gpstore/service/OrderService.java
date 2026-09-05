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
import org.springframework.data.domain.PageRequest;

import com.gpstore.dto.response.AdminNewOrdersSinceResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final com.gpstore.pricing.DeliveryPricingService deliveryPricingService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final InvoiceService invoiceService;
    private final TaxService taxService;
    private final com.gpstore.catalog.shop.ShopCatalog shopCatalog;
    private final com.gpstore.platform.ShopTradingGate shopTradingGate;
    private final com.gpstore.ordergroup.OrderGroupRepository orderGroupRepository;
    private final com.gpstore.platform.ShopRepository shopRepository;
    private final com.gpstore.platform.ShopScopeSwitch shopScopeSwitch;
    private final com.gpstore.repository.DeliveryRepository deliveryRepository;
    private final DeliveryService deliveryService;
    private final com.gpstore.repository.IdempotencyRecordRepository idempotencyRecordRepository;
    private final com.gpstore.config.AfterCommitExecutor afterCommitExecutor;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final com.gpstore.repository.OutboxEventRepository outboxEventRepository;
    // @Lazy: PaymentService depends on OrderService already, so this pair is
    // circular and would fail context startup without it - same reason
    // DeliveryService above is lazy.
    private final PaymentService paymentService;
    private final boolean requireIdempotencyKey;

    private final com.gpstore.store.DeliveryScheduleService deliveryScheduleService;

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
            com.gpstore.pricing.DeliveryPricingService deliveryPricingService,
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
            com.gpstore.config.AfterCommitExecutor afterCommitExecutor,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.gpstore.repository.OutboxEventRepository outboxEventRepository,
            @org.springframework.context.annotation.Lazy PaymentService paymentService,
            com.gpstore.store.DeliveryScheduleService deliveryScheduleService,
            @org.springframework.beans.factory.annotation.Value("${orders.require-idempotency-key:true}")
            boolean requireIdempotencyKey,
            com.gpstore.catalog.shop.ShopCatalog shopCatalog,
            com.gpstore.platform.ShopTradingGate shopTradingGate,
            com.gpstore.ordergroup.OrderGroupRepository orderGroupRepository,
            com.gpstore.platform.ShopRepository shopRepository,
            com.gpstore.platform.ShopScopeSwitch shopScopeSwitch) {

        this.repository = repository;
        this.deliveryScheduleService = deliveryScheduleService;
        this.orderItemRepository = orderItemRepository;
        this.customerService = customerService;
        this.addressService = addressService;
        this.cartItemService = cartItemService;
        this.inventoryService = inventoryService;
        this.paymentRepository = paymentRepository;
        this.couponService = couponService;
        this.deliveryEstimateService = deliveryEstimateService;
        this.deliveryFeeService = deliveryFeeService;
        this.deliveryPricingService = deliveryPricingService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.invoiceService = invoiceService;
        this.taxService = taxService;
        this.deliveryRepository = deliveryRepository;
        this.deliveryService = deliveryService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.afterCommitExecutor = afterCommitExecutor;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.outboxEventRepository = outboxEventRepository;
        this.paymentService = paymentService;
        this.requireIdempotencyKey = requireIdempotencyKey;
        this.shopCatalog = shopCatalog;
        this.shopTradingGate = shopTradingGate;
        this.orderGroupRepository = orderGroupRepository;
        this.shopRepository = shopRepository;
        this.shopScopeSwitch = shopScopeSwitch;
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
        // Fetch-joined: OrderDetailResponse renders the items, each item's
        // variant and product, and the address - all lazy, so a plain
        // findById paid an N+1 to build one response.
        Order order = repository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!isAdmin && (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId))) {
            throw new ResourceNotFoundException("Order not found");
        }

        var delivery = deliveryRepository.findByOrderId(orderId).orElse(null);

        // The payment row, because the order's own payment_status column is a
        // second copy written once at checkout and never updated - a COD a
        // rider settled an hour ago still reads COD_PENDING from it. Passed
        // for the CUSTOMER's view as well as the shop's: being told to have
        // cash ready for an order already paid for is the same wrong answer
        // either way, and the split is the customer's own money.
        var payment = paymentRepository.findByOrderId(orderId).orElse(null);

        // The same endpoint serves a customer looking at their own order and
        // an admin looking at anyone's, and only one of them may see a private
        // product's real name. isAdmin is already established above for the
        // ownership check, so the privacy decision rides on the authorisation
        // that was already made rather than on a second, separate judgement.
        return com.gpstore.dto.response.OrderDetailResponse.from(order, delivery, isAdmin, payment);
    }

    /**
     * Read-only - does NOT redeem the coupon, lock inventory, or create
     * anything. Lets the app show a real cost breakdown (subtotal, discount,
     * delivery fee, estimated total) before the customer commits to placing
     * the order, instead of only finding out the final cost afterward.
     */
    @Transactional(readOnly = true)
    @io.micrometer.core.annotation.Timed(value = "checkout.preview", description = "Checkout preview: cart pricing, deliverability and ETA", percentiles = {0.5, 0.95, 0.99})
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

        // THE PREVIEW IS PER SHOP, AND THEN ADDED UP (§16). A basket spanning
        // two kiranas is two deliveries, two delivery fees and two shops that
        // each have to reach the address - so the customer is shown that
        // breakdown before they commit, rather than one blended number that
        // hides which half is the expensive one.
        //
        // Each shop's half is computed INSIDE that shop's scope, so its
        // prices, its stock, its delivery pricing settings and its distance
        // from the address are all its own.
        java.util.Map<Long, List<CartItem>> byShop = groupByShop(cartItems);

        // Which shop's offer this code is, if any - see shopIssuingCoupon.
        Long couponShopId = shopIssuingCoupon(couponCode, byShop.keySet());
        String couponError = null;
        if (couponCode != null && !couponCode.isBlank() && couponShopId == null) {
            couponError = "That coupon is not offered by any of the shops in your basket.";
        }

        List<com.gpstore.dto.response.CheckoutPreviewResponse.ShopBreakdown> perShop =
                new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal effectiveDeliveryFee = BigDecimal.ZERO;
        boolean deliverable = true;
        boolean freeDeliveryApplied = false;
        Integer estimatedMinutes = null;

        for (java.util.Map.Entry<Long, List<CartItem>> shopBasket : byShop.entrySet()) {
            final Long shopId = shopBasket.getKey();
            final List<CartItem> shopItems = shopBasket.getValue();
            final boolean couponHere = shopId.equals(couponShopId);
            final String code = couponCode;

            var shopPreview = shopScopeSwitch.within(shopId,
                    () -> previewOneShop(shopId, shopItems, address, couponHere ? code : null));

            perShop.add(shopPreview);
            subtotal = subtotal.add(shopPreview.subtotal());
            discountAmount = discountAmount.add(shopPreview.discountAmount());
            effectiveDeliveryFee = effectiveDeliveryFee.add(shopPreview.deliveryFee());
            deliverable = deliverable && shopPreview.deliverable();
            freeDeliveryApplied = freeDeliveryApplied || shopPreview.freeDeliveryApplied();
            if (shopPreview.estimatedDeliveryMinutes() != null) {
                // The whole basket is there when the SLOWEST shop has arrived.
                estimatedMinutes = estimatedMinutes == null
                        ? shopPreview.estimatedDeliveryMinutes()
                        : Math.max(estimatedMinutes, shopPreview.estimatedDeliveryMinutes());
            }
            if (couponHere && shopPreview.couponError() != null) {
                couponError = shopPreview.couponError();
            }
        }

        BigDecimal estimatedTotal = subtotal.subtract(discountAmount).add(effectiveDeliveryFee);

        return new com.gpstore.dto.response.CheckoutPreviewResponse(
                subtotal, discountAmount, effectiveDeliveryFee, estimatedTotal,
                freeDeliveryApplied, deliverable, estimatedMinutes, couponError, perShop);
    }

    /**
     * One shop's half of a checkout preview.
     *
     * Runs inside that shop's scope, so every number it produces - the prices,
     * the delivery quote, the distance, the coupon - belongs to that shop.
     * Under one shop this is the whole preview, computed exactly as it always
     * was.
     */
    private com.gpstore.dto.response.CheckoutPreviewResponse.ShopBreakdown previewOneShop(
            Long shopId, List<CartItem> cartItems, Address address, String couponCode) {

        java.util.Map<Long, com.gpstore.catalog.shop.ShopProductVariant> listings =
                shopCatalog.listingsForShop(shopId, cartItems.stream()
                        .map(i -> i.getProductVariant() == null ? null : i.getProductVariant().getId())
                        .toList());

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
            subtotal = subtotal.add(shopPriceOf(variant, listings)
                    .multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        boolean deliverable = deliveryEstimateService.isWithinServiceableRadius(
                address.getLatitude(), address.getLongitude());

        BigDecimal deliveryFee = BigDecimal.ZERO;
        boolean freeDeliveryApplied = false;
        Integer estimatedMinutes = null;
        String couponError = null;
        BigDecimal discountAmount = BigDecimal.ZERO;

        if (deliverable) {
            // ONE CALL, ONE PRICE. The distance tiers, the weight surcharge and
            // the margin subsidy are all decided together in
            // DeliveryPricingService - the preview and the real order both go
            // through it, so the number a customer is shown at checkout is
            // produced by the same code that charges them.
            com.gpstore.pricing.DeliveryQuote quote =
                    deliveryPricingService.quoteForCart(cartItems, address);
            deliveryFee = quote.finalCharge();
            freeDeliveryApplied = quote.freeDelivery();
            estimatedMinutes = deliveryEstimateService.estimateMinutes(
                    address.getLatitude(), address.getLongitude());
        }

        if (couponCode != null && !couponCode.isBlank()) {
            try {
                AppliedCoupon applied = couponService.preview(couponCode, subtotal, deliveryFee);
                discountAmount = applied.merchandiseDiscount();
                deliveryFee = applied.deliveryFeeDue(deliveryFee);
                if (deliverable && deliveryFee.signum() == 0) {
                    freeDeliveryApplied = true;
                }
            } catch (BadRequestException ex) {
                couponError = ex.getMessage();
            }
        }

        String shopName = shopRepository.findById(shopId)
                .map(com.gpstore.platform.Shop::getDisplayName).orElse(null);

        return new com.gpstore.dto.response.CheckoutPreviewResponse.ShopBreakdown(
                shopId, shopName, cartItems.size(), subtotal, discountAmount, deliveryFee,
                subtotal.subtract(discountAmount).add(deliveryFee),
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
    @io.micrometer.core.annotation.Timed(value = "checkout.place_order", description = "Order placement critical path (locks + commit)", percentiles = {0.5, 0.95, 0.99})
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request, Long customerId, String idempotencyKey) {
        try {
            return transactionTemplate.execute(status -> placeOrderInTransaction(request, customerId, idempotencyKey));
        } catch (IdempotencyRaceException raced) {
            // The insert TX is already rolled back. Reading the winner here
            // is a new transaction, so a completed checkout can replay
            // instead of always 409ing the unique-constraint loser.
            return replayAfterIdempotencyRace(customerId, idempotencyKey);
        }
    }

    private PlaceOrderResponse placeOrderInTransaction(PlaceOrderRequest request, Long customerId, String idempotencyKey) {

        if (request == null) {
            throw new BadRequestException("Request cannot be null");
        }

        // Can this storefront trade at all? Suspended by the platform, closed
        // for good, or under a merchant who has been removed - none of those
        // may take a customer's money, whatever the shop's own open/closed
        // switch says. See ShopTradingGate for why the two are separate
        // questions.
        shopTradingGate.requireCanAcceptOrders();

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

        // The cart is read ONCE, here, and every later step reuses this list.
        //
        // It used to be read twice per checkout: once by
        // computeRequestFingerprint (which has to hash the basket) and again
        // further down to actually build the order. Hibernate's first-level
        // cache deduplicates the customer row across those two paths, but not
        // the cart query - a JPQL query always goes to the database - so the
        // second read was a genuine extra round trip on every single
        // checkout, and it is a JOIN across cart_items, product_variants and
        // products rather than a cheap one. Measured on a 10-item cart:
        // place-order went from 26 statements to 25, and the
        // fingerprint-mismatch path from 3 cart reads to 1.
        //
        // Loaded before the idempotency block rather than after because the
        // fingerprint needs it and the fingerprint is computed before the
        // lookup. That is not a change: computeRequestFingerprint was already
        // issuing both of these queries at exactly this point.
        Customer customer = customerService.getById(customerId);
        List<CartItem> cartItems = (customer != null && customer.getCart() != null)
                ? cartItemService.getCartItemsForCheckout(customer.getCart().getId())
                : null;

        String fingerprint = hasIdempotencyKey ? computeRequestFingerprint(request, customerId, cartItems) : null;

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
                        && !(cartItems == null || cartItems.isEmpty())) {
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
                // Another request with the same key won the insert. This TX is
                // rollback-only, so we cannot replay here - placeOrder()
                // catches IdempotencyRaceException after rollback and re-reads.
                throw new IdempotencyRaceException(raceLost);
            }
        }

        // Null check stays HERE rather than at the load above, so a caller
        // with a valid Idempotency-Key still gets its replay before this can
        // fire. (customerId comes from the authenticated principal, so this
        // is a guard against a deleted account mid-session, not a normal path.)
        if (customer == null) {
            throw new ResourceNotFoundException("Customer not found");
        }

        // THE SERVER'S CLOCK, READ ONCE, and used for both the order's
        // timestamp and its delivery window below.
        //
        // Read once rather than twice because an order placed at 20:59:59.999
        // would otherwise be stamped inside the window and scheduled outside
        // it - a real, if narrow, disagreement between what the receipt says
        // and what the van does. One instant cannot contradict itself.
        final java.time.Instant placedAt = deliveryScheduleService.now();

        // THE BACKEND'S ORDER GATE. Disabling the button in Flutter stops an
        // honest customer; it does not stop an app left open across the switch
        // being flipped, a replayed request, or a script. This is the check
        // that counts, and it sits here - after the idempotency replay above,
        // so a retry of an order placed while the shop was open still
        // completes, and before any inventory row is locked, so a refused
        // order costs nothing.
        //
        // Computed once into a snapshot rather than asked three separate
        // questions: acceptance, delivery type and delivery date all come from
        // the same instant and the same settings read, so they cannot describe
        // three slightly different moments, and checkout costs one closures
        // query rather than three.
        // WHETHER THE SHOP IS TAKING ORDERS IS ASKED PER SHOP, in the loop
        // below, against that shop's own hours and its own closure message.
        // It used to be asked once here, of whichever shop the request
        // resolved to - which under a marketplace is the wrong shop for every
        // other half of the basket, and which read the settings a second time
        // for the shop it was right about.

        // Throws if the address doesn't belong to this customer.
        Address address = addressService.getOwnedAddress(request.getAddressId(), customerId);

        // Serviceability is checked PER SHOP, below, once the basket has been
        // grouped - see requireShopDelivers. The check that used to be here
        // asked the same question of one configured store, which under a
        // marketplace is the wrong store for every shop but one; asking it
        // twice would also be a second round trip on the checkout path for an
        // answer already obtained.

        if (customer.getCart() == null) {
            throw new BadRequestException("Customer cart not found");
        }

        Long cartId = customer.getCart().getId();

        // Deliberately NOT re-read here - see the single load above. The
        // empty-cart rejection stays at this position, after the idempotency
        // block, because a legitimate retry of a completed checkout arrives
        // with an empty cart and must replay rather than be rejected.
        if (cartItems == null || cartItems.isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }

        // ---------------------------------------------------------------
        // THE SPLIT (§16).
        //
        // One basket becomes one order PER SHOP, under one group. Each shop
        // packs, prices, delivers and is paid for its own half, and each of
        // those orders then has its own lifecycle - one can be cancelled or
        // refunded while the other is out for delivery.
        //
        // GROUPED BY THE LINE'S OWN shop_id, which CartService stamped from
        // the shop the add-to-cart request resolved to. Not by anything in
        // this request: a shop id a customer could send would be a shop id
        // that moves an item into another shop's order.
        //
        // A SINGLE-SHOP BASKET TAKES EXACTLY THIS PATH and produces exactly
        // one order, which is the order it always produced. There is no
        // "if more than one shop" branch anywhere, because a rare branch is
        // one nobody exercises until the day it matters.
        // ---------------------------------------------------------------
        java.util.Map<Long, List<CartItem>> byShop = groupByShop(cartItems);

        // EVERY SHOP IN THE BASKET HAS TO REACH THIS ADDRESS, and each one is
        // asked separately against its own radius. A basket spanning two
        // kiranas where only one delivers to the customer is not an order
        // somebody can fulfil, and finding that out after the money moved
        // would be finding it out too late.
        for (Long shopId : byShop.keySet()) {
            requireShopDelivers(shopId, address);
        }

        // A COUPON BELONGS TO ONE SHOP, so it comes off one shop's half of the
        // basket - the shop that issued it. Applying it to every shop would
        // have the other merchants funding a discount they never offered, and
        // applying it to whichever shop happened to be first would be worse
        // still: arbitrary, and different on the next checkout.
        Long couponShopId = shopIssuingCoupon(request.getCouponCode(), byShop.keySet());
        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()
                && couponShopId == null) {
            throw new BadRequestException(
                    "That coupon is not offered by any of the shops in your basket.");
        }

        com.gpstore.ordergroup.OrderGroup group = new com.gpstore.ordergroup.OrderGroup();
        group.setGroupNumber(OrderNumberGenerator.generateGroupNumber(
                repository.nextOrderNumberSequenceValue()));
        group.setCustomerId(customerId);
        group.setAddressId(address.getId());
        group.setShopCount(byShop.size());
        group.setTotalAmount(BigDecimal.ZERO);
        group = orderGroupRepository.save(group);

        List<ShopOrder> placed = new ArrayList<>();
        BigDecimal groupTotal = BigDecimal.ZERO;

        for (java.util.Map.Entry<Long, List<CartItem>> shopBasket : byShop.entrySet()) {
            Long shopId = shopBasket.getKey();

            // EACH SHOP'S ORDER IS BUILT INSIDE THAT SHOP'S SCOPE.
            //
            // Not for the read filter - a Hibernate filter is fixed when the
            // persistence session opens, and a checkout that visits three
            // shops is one session, which is why the prices and the stock rows
            // below are named explicitly instead. It is for the WRITE side:
            // TenantEntityListener stamps every shop-owned row from the scope
            // on the thread, and the scope this request arrived with is the
            // shop the customer was browsing. Without this, the second shop's
            // order, its payment, its delivery and its invoice would all be
            // stamped with the FIRST shop's id - which is one merchant's order
            // filed in another merchant's books.
            //
            // The instant is the checkout's, so every shop's order carries the
            // same "placed at". The store status is read per shop: when each
            // kirana opens, and how long it needs to pack, is its own
            // business, and a delivery promise borrowed from the shop next
            // door is a promise nobody made.
            final com.gpstore.ordergroup.OrderGroup thisGroup = group;
            final boolean couponHere = couponShopId != null && couponShopId.equals(shopId);
            ShopOrder shopOrder = shopScopeSwitch.within(shopId,
                    () -> placeOneShopOrder(request, customer, address, shopBasket.getValue(),
                            thisGroup, placedAt, requireAcceptingOrders(shopId, placedAt),
                            couponHere));
            placed.add(shopOrder);
            groupTotal = groupTotal.add(shopOrder.order().getTotalAmount());
        }

        group.setTotalAmount(groupTotal);
        group = orderGroupRepository.save(group);

        // ONE record per checkout, pointing at the first shop's order.
        //
        // The key identifies the CHECKOUT, not one of its orders, so a replay
        // has to find its way back to the whole group - which it does through
        // that order's own group id. Storing the group id here as well would
        // be a second copy of the same fact.
        if (idempotencyRecord != null) {
            idempotencyRecord.setOrderId(placed.get(0).order().getId());
            idempotencyRecordRepository.save(idempotencyRecord);
        }

        // Cleared ONCE, after every shop's order is built. Clearing it inside
        // the loop would empty the basket the second shop still has to read.
        cartItemService.clearCart(cartId);

        return respondWith(group, placed, paymentService.parsePaymentMethod(request.getPaymentMethod()));
    }

    /**
     * Refuses a basket containing a shop that will not deliver to this address.
     *
     * Asked inside the shop's own scope so the radius and the coordinates are
     * that shop's - see DeliveryEstimateService.origin. Under one shop this is
     * the same check, against the same numbers, that checkout has always run.
     */
    private void requireShopDelivers(Long shopId, Address address) {
        boolean deliverable = shopScopeSwitch.within(shopId,
                () -> deliveryEstimateService.isWithinServiceableRadius(
                        address.getLatitude(), address.getLongitude()));
        if (!deliverable) {
            double radius = shopScopeSwitch.within(shopId,
                    deliveryEstimateService::getMaxDeliveryRadiusKm);
            throw new BadRequestException(
                    "This address is outside our delivery range (" + radius + " km). "
                            + "Please choose a different address or add location coordinates to this one.");
        }
    }

    /**
     * Which shop in this basket offers that coupon, if any.
     *
     * Asked shop by shop, inside each shop's scope, because a coupon code is
     * unique WITHIN a shop and not across the marketplace - two kiranas may
     * both run a "DIWALI10", and they are different offers with different
     * money behind them.
     */
    private Long shopIssuingCoupon(String couponCode, java.util.Collection<Long> shopIds) {
        if (couponCode == null || couponCode.isBlank()) {
            return null;
        }
        for (Long shopId : shopIds) {
            boolean offered = shopScopeSwitch.within(shopId,
                    () -> couponService.isOfferedHere(couponCode));
            if (offered) {
                return shopId;
            }
        }
        return null;
    }

    /**
     * That shop's hours, and a refusal if it is not taking orders.
     *
     * ASKED OF EACH SHOP IN THE BASKET. One kirana closing early does not
     * close the other, and the message a customer sees has to be the words
     * that shop wrote - "back at 9am" reads very differently from a generic
     * apology, and from the shop next door's.
     */
    private com.gpstore.store.StoreStatus requireAcceptingOrders(Long shopId, java.time.Instant placedAt) {
        com.gpstore.store.StoreStatus status = storeStatusOf(shopId, placedAt);
        if (!status.acceptingOrders()) {
            String message = status.closureReason();
            throw new ConflictException(
                    message != null && !message.isBlank()
                            ? message
                            : "The shop has paused new orders for the moment. "
                                    + "You can keep browsing, and your cart will be waiting.");
        }
        return status;
    }

    /**
     * When that shop opens, and what it can promise.
     *
     * READ INSIDE THAT SHOP'S SCOPE, because the schedule and the operations
     * settings behind it are per shop (V49) and are found by the shop in
     * scope. Without this every shop's order in a group would inherit the
     * hours of whichever shop the request happened to resolve to.
     */
    private com.gpstore.store.StoreStatus storeStatusOf(Long shopId, java.time.Instant placedAt) {
        return shopScopeSwitch.within(shopId, () -> deliveryScheduleService.getStoreStatusAt(placedAt));
    }

    /**
     * The basket, in shop order.
     *
     * SORTED BY SHOP ID, and that is not cosmetic. Two customers checking out
     * baskets that overlap two shops must take that pair of shops in the same
     * sequence, for the same reason inventory locks are taken in ascending
     * variant id - opposite orders deadlock. A LinkedHashMap over a sorted key
     * set is what makes the sequence deterministic.
     *
     * A LINE WITH NO SHOP CANNOT BE CHECKED OUT. V51 backfilled every existing
     * line and CartService stamps every new one, so this is unreachable - and
     * it refuses rather than guessing, because guessing would put somebody
     * else's item into a shop's order.
     */
    private static java.util.Map<Long, List<CartItem>> groupByShop(List<CartItem> cartItems) {
        java.util.Map<Long, List<CartItem>> byShop = new java.util.LinkedHashMap<>();
        List<CartItem> sorted = cartItems.stream()
                .sorted(java.util.Comparator.comparing(
                        item -> item.getShopId() == null ? Long.MIN_VALUE : item.getShopId()))
                .toList();
        for (CartItem item : sorted) {
            if (item.getShopId() == null) {
                throw new ConflictException(
                        "An item in your basket is no longer linked to a shop. Please remove and "
                                + "add it again.");
            }
            byShop.computeIfAbsent(item.getShopId(), shop -> new ArrayList<>()).add(item);
        }
        return byShop;
    }

    /**
     * One order, and the payment created with it.
     *
     * A record rather than a bare Order because the response needs the payment
     * status and the UPI link, and re-reading the payment to get them would be
     * a query for something this method already had in its hand.
     */
    private record ShopOrder(Order order, com.gpstore.entity.Payment payment) {}

    /**
     * The checkout's answer, shaped so an app that has never heard of groups
     * still works.
     *
     * BACKWARD COMPATIBLE ON PURPOSE. orderId, orderNumber, paymentStatus and
     * upiPaymentLink still describe ONE order - the first shop's - because
     * that is what every APK already on a customer's phone reads, and those
     * builds outnumber anything that knows about this change. The group and
     * the per-shop breakdown are added beside them, so a single-shop checkout
     * answers byte-for-byte what it always did.
     */
    private PlaceOrderResponse respondWith(com.gpstore.ordergroup.OrderGroup group,
                                           List<ShopOrder> placed,
                                           PaymentMethod paymentMethod) {
        ShopOrder primary = placed.get(0);

        PlaceOrderResponse response = new PlaceOrderResponse();
        response.setSuccess(true);
        response.setOrderId(primary.order().getId());
        response.setOrderNumber(primary.order().getOrderNumber());
        response.setMessage("Order placed successfully.");
        response.setPaymentStatus(nameOf(primary.payment().getPaymentStatus()));
        response.setUpiPaymentLink(paymentService.upiLinkFor(primary.order(), paymentMethod));

        response.setOrderGroupId(group.getId());
        response.setOrderGroupNumber(group.getGroupNumber());
        response.setShopOrders(placed.stream()
                .map(shopOrder -> new PlaceOrderResponse.ShopOrderSummary(
                        shopOrder.order().getId(),
                        shopOrder.order().getOrderNumber(),
                        shopOrder.order().getShopId(),
                        shopOrder.order().getTotalAmount(),
                        shopOrder.order().getDeliveryFee(),
                        nameOf(shopOrder.payment().getPaymentStatus()),
                        paymentService.upiLinkFor(shopOrder.order(), paymentMethod)))
                .toList());
        return response;
    }

    /**
     * Builds ONE shop's order out of the lines that came off that shop's shelf.
     *
     * THE WHOLE MULTI-SHOP SPLIT IS THE CALLER'S LOOP OVER THIS METHOD. There
     * is no per-shop code anywhere: the same routine that placed the single
     * shop's order before Slice 6 places each shop's order now, given a
     * narrower list of cart lines. A basket from one shop runs it once, and
     * that is the same order it always produced.
     */
    private ShopOrder placeOneShopOrder(PlaceOrderRequest request,
                                        Customer customer,
                                        Address address,
                                        List<CartItem> cartItems,
                                        com.gpstore.ordergroup.OrderGroup group,
                                        java.time.Instant placedAt,
                                        com.gpstore.store.StoreStatus storeStatus,
                                        boolean applyCoupon) {
        // Whose half this is, taken off the cart lines themselves. Every line
        // handed to this method carries the same shop - groupByShop is what
        // guarantees it - so reading the first is reading all of them.
        final Long shopId = cartItems.get(0).getShopId();

        // THIS SHOP'S HALF OF THE BASKET, and nothing else. Everything below
        // reads `cartItems`, which the caller has already narrowed to the
        // lines that came off this shop's shelf - so the stock it locks, the
        // prices it charges, the delivery it quotes and the payment it creates
        // are all that shop's, and nothing below branches on which shop it is.
        // That is what stops there being one code path per shop.
        //
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

            // THIS SHOP'S STOCK ROW, NAMED. Same reason as the prices above:
            // one transaction visits several shops, and the filter was fixed
            // when the session opened. Without the shop id, the second shop's
            // half of a basket would lock and decrement whichever row the
            // query found first - which is the first shop's.
            Inventory inventory = inventoryService
                    .getByProductVariantForUpdate(item.getProductVariant().getId(), shopId);

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

        // WHICH CHECKOUT THIS ORDER CAME FROM. The group is what the customer
        // thinks they placed; this order is one shop's part of it, and it is
        // the link that lets a customer see the whole thing while each shop
        // works only its own.
        order.setOrderGroupId(group.getId());

        // WHERE THIS ORDER GOES, COPIED NOW AND NEVER RE-READ.
        //
        // order.address is a foreign key to the customer's live saved address,
        // and editing that address rewrites the row in place - coordinates
        // included. Before this snapshot existed, a customer who moved house
        // and corrected their saved address silently redirected every order
        // still pointing at it, including one already packed and on a bike.
        //
        // Taken from the same `address` the ownership and serviceability
        // checks above ran against, and stamped with the same placedAt instant
        // the order date and delivery window come from - so the destination,
        // the timestamp and the delivery promise cannot disagree with each
        // other about which moment this order was placed at.
        order.captureDeliverySnapshot(
                address, LocalDateTime.ofInstant(placedAt, java.time.ZoneOffset.UTC));

        order.setOrderDate(LocalDateTime.ofInstant(placedAt, java.time.ZoneOffset.UTC));

        // WHEN THIS ARRIVES, DECIDED HERE AND ONLY HERE.
        //
        // Nothing from the request contributes. A delivery date in a request
        // body is a value the customer's phone chose, and a phone whose clock
        // is a day slow - or whose owner edited the JSON - would otherwise
        // book a van for a day of its choosing. The request DTO deliberately
        // has no field for it, so there is nothing to accidentally read.
        //
        // Both may be null when the shop is closed past the lookahead and the
        // owner forced ordering ON anyway: the order is real and will be
        // delivered when the shop reopens, and recording a date nobody can
        // honour would be worse than recording none.
        order.setDeliveryType(storeStatus.deliveryType());
        order.setScheduledDeliveryDate(storeStatus.deliveryDate());

        // Validated up front, through the same parser PaymentService uses, so
        // an unsupported method fails before any inventory is touched rather
        // than after the order is half-built.
        final PaymentMethod paymentMethod = paymentService.parsePaymentMethod(request.getPaymentMethod());

        if (paymentMethod == PaymentMethod.COD) {
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

        // THE PRICE CHARGED IS THIS SHOP'S PRICE. Read once for the whole
        // basket and reused for the order lines below, so the total and the
        // lines cannot disagree - the two used to read the same catalogue
        // field, and now read the same map.
        java.util.Map<Long, com.gpstore.catalog.shop.ShopProductVariant> listings =
                shopCatalog.listingsForShop(shopId, cartItems.stream()
                        .map(i -> i.getProductVariant() == null ? null : i.getProductVariant().getId())
                        .toList());

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CartItem item : cartItems) {
            totalAmount = totalAmount.add(shopPriceOf(item.getProductVariant(), listings)
                    .multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // Coupon is validated and redeemed (usage count incremented, under a row
        // lock) here, inside the same transaction as the rest of checkout - so a
        // failure anywhere else in placeOrder rolls the redemption back too.
        // Delivery is quoted first so a DELIVERY_FLAT coupon can reduce the
        // fee the customer actually pays without touching merchandise.
        com.gpstore.pricing.DeliveryQuote quote =
                deliveryPricingService.quoteForCart(cartItems, address);
        BigDecimal quotedDeliveryFee = quote.finalCharge();
        boolean freeDeliveryApplied = quote.freeDelivery();

        AppliedCoupon applied = AppliedCoupon.none();
        if (applyCoupon && request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            applied = couponService.redeem(request.getCouponCode(), totalAmount, quotedDeliveryFee);
            order.setAppliedCouponCode(request.getCouponCode().toUpperCase());
            order.setDiscountAmount(applied.merchandiseDiscount());
        }

        BigDecimal deliveryFee = applied.deliveryFeeDue(quotedDeliveryFee);
        if (deliveryFee.signum() == 0) {
            freeDeliveryApplied = true;
        }

        order.setTotalAmount(totalAmount.subtract(applied.merchandiseDiscount()));

        order.setDeliveryFee(deliveryFee);
        order.setFreeDeliveryApplied(freeDeliveryApplied);

        // The whole working, kept. An admin screen that recalculated this
        // later would show a different number - settings get edited, costs get
        // corrected - and the customer was charged what they were charged.
        order.setDeliveryDistanceKm(quote.distanceKm());
        order.setDeliveryWeightKg(quote.totalWeightKg());
        order.setDeliveryDistanceCharge(quote.distanceCharge());
        order.setDeliveryWeightCharge(quote.weightCharge());
        order.setDeliveryNormalCharge(quote.normalCharge());
        order.setDeliveryOrderProfit(quote.orderProfit());
        order.setDeliverySubsidy(quote.subsidy());
        order.setDeliveryPricingNotes(quote.hasWarnings()
                ? truncate(String.join(" | ", quote.warnings()), 1000) : null);

        if (deliveryFee.signum() > 0) {
            order.setTotalAmount(order.getTotalAmount().add(deliveryFee));
        }

        order = repository.save(order);

        List<OrderItem> newOrderItems = new ArrayList<>();

        for (CartItem item : cartItems) {

            OrderItem orderItem = new OrderItem();

            orderItem.setOrder(order);
            orderItem.setProductVariant(item.getProductVariant());
            orderItem.setQuantity(item.getQuantity());
            BigDecimal linePrice = shopPriceOf(item.getProductVariant(), listings);
            orderItem.setPrice(linePrice);
            orderItem.setTotalPrice(linePrice.multiply(BigDecimal.valueOf(item.getQuantity())));
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

        // Durable record of the post-order work that must not be lost.
        // INSERTed inside THIS transaction on purpose: it commits with the
        // order or not at all, so there is no window in which an order
        // exists but its invoice work was never recorded. Contrast the
        // executor below, which is in-memory and does not survive the
        // redeploys this service gets on every push.
        outboxEventRepository.save(com.gpstore.entity.OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, order.getId(), OutboxWorker.EVENT_ORDER_PLACED));

        // Payment row created HERE, inside the order transaction, instead of
        // leaving the client to make a second HTTP request for it.
        //
        // Checkout was: POST /orders/place, wait, POST /payments, wait. The
        // second request paid a full round trip - auth, rate limit, routing,
        // TLS - for what is a single INSERT with no external dependency (UPI
        // link generation is local string building, see
        // PaymentService.upiLinkFor). Folding it in removes that entire round
        // trip from the customer's critical path.
        //
        // It also closes a real correctness gap rather than only saving
        // time: in the two-request flow an order could exist with NO payment
        // at all if the second call never arrived (app killed, network lost,
        // process died between them). Creating it in the same transaction
        // means the two commit together or not at all.
        final com.gpstore.entity.Payment newPayment =
                paymentService.createPaymentForNewOrder(order, paymentMethod);

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
            if (placedOrder.getOrderStatus() == OrderStatus.CONFIRMED) {
                notificationService.notifyAdminsOfNewOrder(placedOrder);
            }
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
        afterCommitExecutor.runAfterCommit("Post-order side effects", placedOrderId, afterCommitWork);

        return new ShopOrder(order, newPayment);
    }

    private PlaceOrderResponse replayAfterIdempotencyRace(Long customerId, String idempotencyKey) {
        return transactionTemplate.execute(status -> {
            Optional<com.gpstore.entity.IdempotencyRecord> winner =
                    idempotencyRecordRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (winner.isPresent() && winner.get().getOrderId() != null) {
                return buildReplayResponse(winner.get().getOrderId());
            }
            throw new ConflictException(
                    "This order is already being processed. Please wait a moment and check your order history before trying again.");
        });
    }

    private static final class IdempotencyRaceException extends RuntimeException {
        IdempotencyRaceException(Throwable cause) {
            super(cause);
        }
    }

    /** Paginated - a repeat customer's order history has no natural upper bound over the years. */
    public org.springframework.data.domain.Page<OrderResponse> getMyOrders(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return toOrderResponses(
                repository.findByCustomerIdOrderByOrderDateDesc(customerId, pageable), false);
    }

    /**
     * Every order in the system, newest first, WITH customer name (the raw
     * entity alone can't show this - Order.customer is hidden from JSON).
     * Paginated at the DB level (ORDER BY + LIMIT/OFFSET in SQL) instead of
     * loading every order row into Java just to sort a handful into view.
     */
    public org.springframework.data.domain.Page<OrderResponse> getAllOrdersForAdmin(
            org.springframework.data.domain.Pageable pageable) {
        return toOrderResponses(repository.findAllByOrderByOrderDateDesc(pageable), true);
    }

    /**
     * Shop-counter soundbox poll. A missing afterId is the arming call: return
     * the current max id and an empty list so the app does not speak every
     * historical order when the admin opens the app. Later calls return at
     * most 20 newer orders, oldest first, so a burst is spoken in order and
     * the next poll continues from the last returned id.
     */
    @Transactional(readOnly = true)
    public AdminNewOrdersSinceResponse getNewOrdersSince(Long afterId) {
        if (afterId == null) {
            return new AdminNewOrdersSinceResponse(repository.findMaxId(), List.of());
        }
        long floor = Math.max(afterId, 0L);
        List<Order> found = repository.findNewSince(floor, PageRequest.of(0, 20));
        return composeNewOrdersSince(floor, found);
    }

    static AdminNewOrdersSinceResponse composeNewOrdersSince(
            long requestedAfterId, List<Order> found) {
        if (found == null || found.isEmpty()) {
            // Stay on the caller's cursor. Jumping to maxId when the query
            // returned nothing would skip orders that are committed but not
            // yet visible to this read.
            return new AdminNewOrdersSinceResponse(requestedAfterId, List.of());
        }
        List<AdminNewOrdersSinceResponse.AdminNewOrderAlert> alerts = new ArrayList<>();
        Long unpaidHold = null;
        Long lastAdvanced = null;
        for (Order order : found) {
            if (order.getOrderStatus() == OrderStatus.PENDING_CONFIRMATION) {
                // HOLD THE CURSOR, DO NOT STOP SCANNING. An abandoned UPI or
                // ONLINE payment sits PENDING_CONFIRMATION for up to
                // payment.upi-timeout-minutes (30) before the expiry sweep
                // clears it. Breaking here meant every CONFIRMED order placed
                // behind it went unannounced for that whole window - one
                // customer closing the payment page silenced the shop counter
                // through a dinner rush. Keep the cursor pinned so the unpaid
                // order is re-examined once it is paid, but let everything
                // behind it through. Re-sending an alert is free: the app
                // claims each order id once in AnnouncementLog (bounded at
                // 200, this page at 20), so a repeat is never spoken twice.
                if (unpaidHold == null) {
                    unpaidHold = order.getId();
                }
                continue;
            }
            if (shouldAnnounceAsNewShopOrder(order)) {
                alerts.add(new AdminNewOrdersSinceResponse.AdminNewOrderAlert(
                        order.getId(),
                        displayNameOf(order),
                        plainAmountOf(order)));
            }
            if (unpaidHold == null) {
                lastAdvanced = order.getId();
            }
        }
        if (unpaidHold != null) {
            // Do not skip past an unpaid ONLINE/UPI order. When it confirms,
            // the next poll must still see it.
            return new AdminNewOrdersSinceResponse(unpaidHold - 1, alerts);
        }
        long after = lastAdvanced != null ? lastAdvanced : requestedAfterId;
        return new AdminNewOrdersSinceResponse(after, alerts);
    }

    static boolean shouldAnnounceAsNewShopOrder(Order order) {
        OrderStatus status = order.getOrderStatus();
        return status == null || status == OrderStatus.CONFIRMED;
    }

    static boolean paymentAllowsConfirm(Payment payment) {
        if (payment == null || payment.getPaymentStatus() == null) {
            return false;
        }
        PaymentStatus status = payment.getPaymentStatus();
        return status == PaymentStatus.SUCCESS
                || status == PaymentStatus.COD_PENDING
                || status == PaymentStatus.COD_RECEIVED;
    }

    private static String displayNameOf(Order order) {
        Customer customer = order.getCustomer();
        if (customer == null) {
            return "a customer";
        }
        String name = customer.getFullName();
        return (name == null || name.isBlank()) ? "a customer" : name.trim();
    }

    private static String plainAmountOf(Order order) {
        BigDecimal total = order.getTotalAmount();
        if (total == null) {
            return "0";
        }
        if (total.stripTrailingZeros().scale() <= 0) {
            return total.toBigInteger().toString();
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
        return toOrderResponses(
                repository.findByCustomerIdOrderByOrderDateDesc(customerId, pageable), false);
    }

    /**
     * Enum name, or null for a null enum.
     *
     * Every status column these read from is nullable in the schema, so
     * calling .name() on one directly is a 500 waiting for the right row.
     * See setPaymentStatus in toOrderResponse for the one that actually
     * happened.
     */
    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * Maps a page of orders, reporting each one's PAYMENT status.
     *
     * ONE QUERY FOR THE WHOLE PAGE. The order's own payment_status column is
     * a second copy of the status, written once at checkout and never
     * updated - twelve places change a Payment's status and none of them
     * touches the order's copy - so a COD a rider settled an hour ago still
     * reads COD_PENDING from it. That is the screen the shop uses to know
     * which deliveries still owe money, so the stale copy is not cosmetic.
     *
     * Batched rather than a lookup per row: fifty orders on a page would
     * otherwise be fifty round trips behind one screen.
     */
    private org.springframework.data.domain.Page<OrderResponse> toOrderResponses(
            org.springframework.data.domain.Page<Order> page, boolean includeCustomerName) {

        List<Long> orderIds = page.getContent().stream().map(Order::getId).toList();
        Map<Long, PaymentStatus> statuses = new HashMap<>();
        if (!orderIds.isEmpty()) {
            for (Payment payment : paymentRepository.findByOrderIdIn(orderIds)) {
                if (payment.getOrder() != null && payment.getPaymentStatus() != null) {
                    statuses.put(payment.getOrder().getId(), payment.getPaymentStatus());
                }
            }
        }
        return page.map(order -> toOrderResponse(order, includeCustomerName, statuses.get(order.getId())));
    }

    private OrderResponse toOrderResponse(Order order, boolean includeCustomerName) {
        return toOrderResponse(order, includeCustomerName, null);
    }

    /**
     * @param paymentStatusOrNull the PAYMENT row's status, when the caller
     *                            looked it up. Null falls back to the order's
     *                            own column, which is the only answer there
     *                            is for an order that has no payment row -
     *                            they predate payment creation moving into
     *                            the order transaction.
     */
    private OrderResponse toOrderResponse(Order order, boolean includeCustomerName,
                                          PaymentStatus paymentStatusOrNull) {
        OrderResponse response = new OrderResponse();

        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setTotalAmount(order.getTotalAmount());
        response.setOrderStatus(nameOf(order.getOrderStatus()));
        // Both nullable for orders that predate the scheduling feature - see
        // OrderResponse, and nameOf, which exists for exactly this.
        response.setDeliveryType(nameOf(order.getDeliveryType()));
        response.setScheduledDeliveryDate(order.getScheduledDeliveryDate());
        // NULLABLE, and 500ing on it took out three endpoints at once.
        //
        // orders.payment_status has no NOT NULL constraint and no default,
        // and order creation only started setting it (COD_PENDING/PENDING,
        // see placeOrder) after orders already existed. Every order older
        // than that code still holds NULL, and nothing ever backfilled them.
        //
        // This mapper backs the admin order list, the admin per-customer
        // order list AND the customer's own order history, so a single
        // legacy row made all three answer 500 - the admin could not open
        // the orders screen at all. Found by probing a running instance
        // against a database with real orders in it; no test caught it,
        // because the orders the tests create are all made by placeOrder
        // and therefore all have a status.
        //
        // Reported as null rather than defaulted to a value: which status a
        // pre-payment-tracking order "really" had is not knowable here, and
        // inventing PENDING for a delivered COD order would be a lie the
        // admin screen then shows as fact.
        response.setPaymentStatus(paymentStatusOrNull != null
                ? paymentStatusOrNull.name()
                : nameOf(order.getPaymentStatus()));
        response.setOrderDate(order.getOrderDate());

        if (includeCustomerName) {
            response.setCustomerName(order.getCustomer() != null ? order.getCustomer().getFullName() : null);
        }

        return response;
    }

    @Transactional
    @io.micrometer.core.annotation.Timed(value = "order.status_update", description = "Order status transition critical path", percentiles = {0.5, 0.95, 0.99})
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
                        // PACKED is what a worker's QR scan writes. It sits
                        // beside READY_TO_DISPATCH rather than replacing it:
                        // the older state is still reachable from the admin
                        // status dropdown and still on live orders, and
                        // deleting a state that production rows hold is how a
                        // deployment breaks every order mid-flight.
                        || (currentStatus == OrderStatus.CONFIRMED && status == OrderStatus.PACKED)
                        || (currentStatus == OrderStatus.PACKING && status == OrderStatus.PACKED)
                        || (currentStatus == OrderStatus.READY_TO_DISPATCH && status == OrderStatus.PACKED)
                        || (currentStatus == OrderStatus.PACKED && status == OrderStatus.OUT_FOR_DELIVERY)
                        || (currentStatus == OrderStatus.PACKED && status == OrderStatus.READY_TO_DISPATCH)
                        || (currentStatus == OrderStatus.OUT_FOR_DELIVERY && status == OrderStatus.DELIVERED);

        if (!validTransition) {
            throw new ConflictException(
                    "Invalid order status transition from " + currentStatus + " to " + status);
        }
        if (currentStatus == OrderStatus.PENDING_CONFIRMATION && status == OrderStatus.CONFIRMED
                && !paymentAllowsConfirm(paymentRepository.findByOrderId(orderId).orElse(null))) {
            throw new ConflictException(
                    "Payment is not confirmed yet. The shop cannot confirm this order until it is paid.");
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

        // Audit stays IN the transaction: it is one INSERT, it is a
        // compliance record, and it should commit or roll back exactly with
        // the transition it describes. Cheap enough that deferring it would
        // trade real durability for negligible latency.
        auditLogService.log("ORDER_STATUS_CHANGED", "Order", savedOrder.getId(),
                "status: " + currentStatus + " -> " + status);

        // The notification does NOT stay: it writes a row and then calls
        // FirebaseMessaging.send(), a blocking network round trip to Google,
        // which was happening while this order's row lock was still held.
        // That put an external service's latency directly in the customer's
        // response time AND in the critical section other requests for this
        // order queue behind. Losing a push on a crash is acceptable; making
        // every status change wait for Google is not.
        final Order notifyOrder = savedOrder;
        final OrderStatus notifyStatus = savedOrder.getOrderStatus();
        if (notifyOrder.getCustomer() != null) {
            notifyOrder.getCustomer().getFcmToken();
        }
        notifyOrder.getOrderNumber();
        afterCommitExecutor.runAfterCommit("Order status notification", savedOrder.getId(), () -> {
            notificationService.notifyOrderStatusChange(notifyOrder, notifyStatus);
            if (currentStatus == OrderStatus.PENDING_CONFIRMATION && notifyStatus == OrderStatus.CONFIRMED) {
                notificationService.notifyAdminsOfNewOrder(notifyOrder);
                deliveryService.autoAssignBestEffort(notifyOrder.getId());
            }
        });

        var delivery = deliveryRepository.findByOrderId(savedOrder.getId()).orElse(null);
        // Re-read fetch-joined purely to BUILD THE RESPONSE. savedOrder is
        // already managed and correct, but its items/variants/products and
        // address are still lazy, so rendering the DTO from it would issue
        // one query per item. This is one extra query that removes several.
        Order forResponse = repository.findByIdWithDetails(savedOrder.getId()).orElse(savedOrder);
        // The payment row, not the order's own stale copy of the status. This
        // path CHANGES a payment (marking a COD delivery received, or moving
        // a cancelled prepaid order to REFUND_PENDING), so returning the
        // order column here would hand the caller back the status it had
        // before the very change it just made.
        return com.gpstore.dto.response.OrderDetailResponse.from(forResponse, delivery, false,
                paymentRepository.findByOrderId(savedOrder.getId()).orElse(null));
    }

    /**
     * callerCustomerId/isAdmin enforce that a customer can only cancel their
     * own order, while staff can cancel any order.
     */
    @Transactional
    @io.micrometer.core.annotation.Timed(value = "order.cancel", description = "Order cancellation critical path", percentiles = {0.5, 0.95, 0.99})
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

        if (!isAdmin && !customerMayCancel(order.getOrderStatus())) {
            throw new ConflictException(
                    "This order can no longer be cancelled. Contact the shop if you need help.");
        }

        order.setOrderStatus(OrderStatus.CANCELLED);

        // ORDER (already locked above) -> PAYMENT -> INVENTORY. Locking the
        // payment row rather than plain-reading it: the expiry sweep and the
        // UPI/COD confirmation paths can be looking at this same payment
        // right now, and its status has to be re-read under the lock before
        // being acted on rather than trusted from an unlocked read.
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);

        // Set below, acted on after the payment row is saved. The provider is
        // NOT called from in here: this method holds the order row, the
        // payment row and every inventory row the order touches, and a slow
        // Cashfree would stall every other write against this order - while a
        // provider timeout would roll the cancellation back, stock and all.
        boolean refundNeedsSending = false;

        if (payment != null) {

            if (payment.getPaymentMethod() == PaymentMethod.COD
                    && payment.getPaymentStatus() == PaymentStatus.COD_PENDING) {

                payment.setPaymentStatus(PaymentStatus.FAILED);

            } else if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {

                payment.setPaymentStatus(PaymentStatus.REFUND_PENDING);

                // WHICH WAY THE MONEY HAS TO TRAVEL BACK, decided here and
                // written down. Until this existed, a cancelled prepaid order
                // sat at REFUND_PENDING with no channel and no refund id,
                // which read to completeRefund as a cash refund - so pressing
                // "complete" stamped it REFUNDED while the customer's money
                // was still at Cashfree. That is the whole bug the refund
                // work set out to kill, surviving on the path that carries
                // most of the traffic.
                boolean cash = PaymentService.isCashRefund(payment);
                payment.setRefundChannel(cash
                        ? Payment.RefundChannel.CASH
                        : Payment.RefundChannel.GATEWAY);
                payment.setRefundAmount(payment.getAmount());
                refundNeedsSending = !cash;

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

        // Audit stays in the transaction - one INSERT, and a cancellation is
        // exactly the kind of event that must not be missing from the record
        // if the process dies a moment later.
        auditLogService.log("ORDER_CANCELLED", "Order", savedOrder.getId(),
                "cancelled by " + (isAdmin ? "admin/staff" : "customer"));

        // Invoice cancellation is ACCOUNTING work, not a nicety: a cancelled
        // order whose invoice stays active still reads as a valid sale for
        // GST purposes. It therefore goes in the durable outbox rather than
        // the fire-and-forget executor - the executor cannot survive the
        // redeploys this service gets on every push, and losing this
        // silently would leave the books wrong with nothing reporting it.
        // The row is written inside THIS transaction, so it commits with the
        // cancellation or not at all.
        outboxEventRepository.save(com.gpstore.entity.OutboxEvent.of(
                OutboxWorker.AGGREGATE_ORDER, savedOrder.getId(), OutboxWorker.EVENT_ORDER_CANCELLED));

        // THE MONEY, on the same terms as the invoice above and for a
        // stronger reason: it is the customer's. The row commits with the
        // cancellation or not at all, so a crash between the two cannot
        // produce a cancelled order that owes a refund nothing will ever
        // send. The worker makes the provider call afterwards, with none of
        // this transaction's locks held, and retries while Cashfree is down.
        if (refundNeedsSending) {
            outboxEventRepository.save(com.gpstore.entity.OutboxEvent.of(
                    OutboxWorker.AGGREGATE_ORDER, savedOrder.getId(),
                    OutboxWorker.EVENT_REFUND_REQUESTED));
        }

        // The push notification is genuinely best-effort and involves a
        // blocking network call to Google (FirebaseMessaging.send), so it
        // leaves the request path entirely. It was previously executed while
        // this order's row lock was still held.
        final Order notifyOrder = savedOrder;
        final OrderStatus notifyStatus = savedOrder.getOrderStatus();
        if (notifyOrder.getCustomer() != null) {
            notifyOrder.getCustomer().getFcmToken();
        }
        notifyOrder.getOrderNumber();
        afterCommitExecutor.runAfterCommit("Order cancellation notification", savedOrder.getId(),
                () -> notificationService.notifyOrderStatusChange(notifyOrder, notifyStatus));

        var delivery = deliveryRepository.findByOrderId(savedOrder.getId()).orElse(null);
        // Re-read fetch-joined purely to BUILD THE RESPONSE. savedOrder is
        // already managed and correct, but its items/variants/products and
        // address are still lazy, so rendering the DTO from it would issue
        // one query per item. This is one extra query that removes several.
        Order forResponse = repository.findByIdWithDetails(savedOrder.getId()).orElse(savedOrder);
        // The payment row, not the order's own stale copy of the status. This
        // path CHANGES a payment (marking a COD delivery received, or moving
        // a cancelled prepaid order to REFUND_PENDING), so returning the
        // order column here would hand the caller back the status it had
        // before the very change it just made.
        return com.gpstore.dto.response.OrderDetailResponse.from(forResponse, delivery, false,
                paymentRepository.findByOrderId(savedOrder.getId()).orElse(null));
    }

    /**
     * Customers may cancel only while the shop has not started packing.
     * PACKING and later are operational: stock is already being picked, a
     * rider may already have the bag. Admin/staff can still cancel those
     * with the usual audit trail.
     */
    static boolean customerMayCancel(OrderStatus status) {
        return status == OrderStatus.PENDING_CONFIRMATION || status == OrderStatus.CONFIRMED;
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
    private String computeRequestFingerprint(PlaceOrderRequest request, Long customerId,
                                            List<CartItem> cartItems) {
        StringBuilder canonical = new StringBuilder()
                .append(customerId).append('|')
                .append(request.getAddressId()).append('|')
                .append(request.getPaymentMethod() == null
                        ? "" : request.getPaymentMethod().trim().toUpperCase()).append('|')
                .append(request.getCouponCode() == null
                        ? "" : request.getCouponCode().trim().toUpperCase()).append('|');

        // Takes the cart as an argument rather than loading it: the caller has
        // already read it, and reading it again here was one wasted round
        // trip per checkout. A null list means "no cart" and hashes the same
        // way an empty one does, which is what the previous load did too.
        if (cartItems != null) {
            cartItems.stream()
                    .filter(item -> item.getProductVariant() != null)
                    .sorted(java.util.Comparator.comparing(item -> item.getProductVariant().getId()))
                    .forEach(item -> canonical
                            .append(item.getProductVariant().getId())
                            .append(':')
                            .append(item.getQuantity())
                            .append(','));
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

        // A replay must return the SAME payment information the original
        // request did, not a subset of it.
        //
        // This response used to stop at the four fields above, and the
        // omission had a concrete cost on the client. checkout_screen.dart
        // treats a null paymentStatus as "this backend is too old to create
        // the payment with the order" and falls back to a second HTTP call,
        // POST /payments. So the retry path - the one that exists precisely
        // because the customer's first attempt was slow or dropped - was the
        // path that turned into two round trips instead of one, and fired an
        // initiatePayment against an order that already had a payment row.
        //
        // Read from the persisted payment rather than recomputed: this is a
        // replay, so the answer is whatever was actually stored for this
        // order, including any status it has moved to since (a UPI payment
        // the customer has since confirmed must not read back as PENDING).
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        if (payment != null) {
            response.setPaymentStatus(nameOf(payment.getPaymentStatus()));
            // Pure local string building from order number and amount (see
            // PaymentService.upiLinkFor) - no gateway call - so the replay
            // reproduces the identical link the first response carried, and
            // null for COD exactly as before.
            response.setUpiPaymentLink(paymentService.upiLinkFor(order, payment.getPaymentMethod()));
        } else if (order.getPaymentStatus() != null) {
            // No payment row: an order placed before payment creation moved
            // into the order transaction. Fall back to the order's own copy
            // of the status so the client still gets a non-null value and
            // stays on the single-request path.
            response.setPaymentStatus(nameOf(order.getPaymentStatus()));
        }

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

        // Sorted by variant id before taking ANY inventory lock, for exactly
        // the same reason placeOrder sorts its cart items - and this path was
        // missing it, which is a real deadlock.
        //
        // The failure it allows: order A contains variants [5, 3] and is
        // being restored, while a concurrent checkout holds a cart of
        // [3, 5]. Restoration locks 5 then waits for 3; the checkout locks 3
        // then waits for 5. Neither can proceed. Postgres eventually kills
        // one with a deadlock error, so it surfaces as a random failed
        // cancellation or checkout under load - the kind of thing that never
        // reproduces on demand and gets written off as a blip.
        //
        // Holding the ORDER lock first prevents two restorations of the SAME
        // order from racing, but it does nothing about a restoration and an
        // unrelated checkout contending for the same INVENTORY rows. Only a
        // globally consistent lock order fixes that, so every path that locks
        // inventory must sort identically: ascending variant id.
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId()).stream()
                .filter(item -> item.getProductVariant() != null)
                .sorted(java.util.Comparator.comparing(item -> item.getProductVariant().getId()))
                .toList();
        for (OrderItem item : items) {
            if (item.getProductVariant() == null) {
                continue;
            }
            // THE ORDER'S OWN SHOP, read off the row we are restoring.
            //
            // This runs from the payment-expiry sweep, which spans shops and
            // therefore has no filter enabled on its session - so the
            // unqualified lookup would have been free to lock and credit
            // whichever shop's stock row it found first. The shop is not a
            // parameter anybody sends; it is a column on the order.
            Inventory inventory = inventoryService.getByProductVariantForUpdate(
                    item.getProductVariant().getId(), order.getShopId());
            if (inventory == null || item.getQuantity() == null) {
                continue;
            }
            inventory.setStock(inventory.getStock() + item.getQuantity());

            // The explicit save() here looks inconsistent with checkout,
            // which relies on Hibernate dirty checking alone - so it is worth
            // recording that it was measured rather than assumed. It costs
            // nothing: save() on an already-managed entity is a merge that
            // returns the same instance, and the UPDATE is emitted once at
            // flush either way. A/B on order-cancel with a 3-item order:
            // 19 statements with it, 19 without.
            //
            // Kept because it is not purely redundant - InventoryService.save
            // runs validateNonNegativeStock, which is a guard worth having on
            // any path that writes stock, even one that only ever adds.
            inventoryService.save(inventory);
        }

        // Set even when the order had no restorable items: the question this
        // answers is "has the restore step run for this order", and running
        // it again for a zero-item order is still pointless work.
        order.setInventoryRestored(true);
        return true;
    }

    /**
     * Keeps a note inside its column. Truncated rather than dropped: a
     * shortened warning still tells an admin something was odd about this
     * order, and a silently discarded one tells them nothing.
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /**
     * What this shop charges for one line, from listings already loaded.
     *
     * REFUSES RATHER THAN GUESSES. A cart line whose item this shop does not
     * list is not something to price from the catalogue and charge for - it is
     * an item the customer cannot buy here, and saying so is the only answer
     * that cannot overcharge or undercharge somebody. Under one shop every
     * priced variant is listed, so this never fires today.
     */
    private BigDecimal shopPriceOf(com.gpstore.entity.ProductVariant variant,
                                   java.util.Map<Long, com.gpstore.catalog.shop.ShopProductVariant> listings) {
        return shopCatalog.priceOf(variant, listings).orElseThrow(() -> new ConflictException(
                (variant != null && variant.getProduct() != null ? variant.getProduct().getName() : "An item")
                        + " is no longer available - please remove it from your cart."));
    }
}
