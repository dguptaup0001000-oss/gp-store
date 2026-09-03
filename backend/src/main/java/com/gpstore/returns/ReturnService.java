package com.gpstore.returns;

import com.gpstore.entity.*;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.*;
import com.gpstore.service.AuditLogService;
import com.gpstore.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Taking goods back, and paying for them only once.
 *
 * THE INVARIANT, and every rule below exists to keep it true: a customer can
 * never be refunded for more units than they bought, and never at a price
 * they did not pay. The request names order lines and quantities; the money
 * is worked out here from the order's own stored prices. Nothing about the
 * amount comes from the client, because the client is a phone and the amount
 * is the shop's money.
 *
 * WHY A REQUEST AND A DECISION, RATHER THAN AN INSTANT REFUND. The goods have
 * to physically come back. A customer tapping "return" has not handed
 * anything over yet, and a shop that refunded on the tap would be paying for
 * items still in someone's kitchen. So the customer asks, the shop looks at
 * what arrives, and approval is the thing that moves money and stock.
 *
 * WHAT APPROVAL DOES, in one transaction: writes the decision, puts the units
 * back into stock, and opens a refund through the ledger. If the refund
 * cannot be opened - the provider refuses, the payment was never made - the
 * whole approval rolls back rather than leaving a shop that has accepted
 * goods with no record of owing for them.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    private final OrderReturnRepository returnRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerRepository customerRepository;
    private final DeliveryRepository deliveryRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final com.gpstore.service.InventoryService inventoryService;
    private final AuditLogService auditLogService;

    private final int windowDays;
    private final int maxLinesPerRequest;

    public ReturnService(OrderReturnRepository returnRepository,
                         OrderRepository orderRepository,
                         OrderItemRepository orderItemRepository,
                         CustomerRepository customerRepository,
                         DeliveryRepository deliveryRepository,
                         PaymentRepository paymentRepository,
                         PaymentService paymentService,
                         com.gpstore.service.InventoryService inventoryService,
                         AuditLogService auditLogService,
                         // Seven days from delivery. Long enough that somebody
                         // who opened the packet on the weekend is not shut
                         // out, short enough that perishable groceries are not
                         // coming back a month later.
                         @Value("${returns.window-days:7}") int windowDays,
                         @Value("${returns.max-lines-per-request:50}") int maxLinesPerRequest) {
        this.returnRepository = returnRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.customerRepository = customerRepository;
        this.deliveryRepository = deliveryRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.auditLogService = auditLogService;
        this.windowDays = windowDays;
        this.maxLinesPerRequest = maxLinesPerRequest;
    }

    /**
     * A customer asks to send items back.
     *
     * @param customerId taken from the token by the controller, never from the body.
     */
    @Transactional
    public OrderReturn request(Long customerId, Long orderId, Map<Long, Integer> lines, String reason) {

        if (lines == null || lines.isEmpty()) {
            throw new BadRequestException("Choose at least one item to return.");
        }
        if (lines.size() > maxLinesPerRequest) {
            throw new BadRequestException("That is more lines than one return can carry.");
        }

        // The order row is locked for the whole check-and-write. Without it,
        // two requests for the last returnable unit both read "one left" and
        // both succeed.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // IDOR: the order has to be theirs. Checked here as well as in
        // SecurityConfig because this is the layer that knows whose it is.
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        requireReturnable(order);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        OrderReturn request = new OrderReturn();
        request.setOrder(order);
        request.setCustomer(customer);
        request.setStatus(OrderReturn.Status.REQUESTED);
        request.setReason(trim(reason, 500));
        request.setRequestedAt(LocalDateTime.now());

        BigDecimal provisional = BigDecimal.ZERO;

        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            Long orderItemId = line.getKey();
            Integer wanted = line.getValue();

            if (wanted == null || wanted <= 0) {
                throw new BadRequestException("Return at least one of each item you choose.");
            }

            OrderItem item = orderItemRepository.findById(orderItemId)
                    .orElseThrow(() -> new BadRequestException("That item is not on this order."));

            // THE LINE MUST BELONG TO THIS ORDER. Without this check a
            // customer could name any order item id in the shop and be
            // refunded its price against their own order - somebody else's
            // expensive line, refunded to them.
            if (item.getOrder() == null || !item.getOrder().getId().equals(order.getId())) {
                throw new BadRequestException("That item is not on this order.");
            }

            int bought = item.getQuantity() == null ? 0 : item.getQuantity();
            int alreadyClaimed = returnRepository.unitsAlreadyClaimedFor(orderItemId);
            int returnable = bought - alreadyClaimed;

            if (wanted > returnable) {
                throw new ConflictException(returnable <= 0
                        ? "That item has already been returned."
                        : "Only " + returnable + " of that item can still be returned.");
            }

            BigDecimal unitPrice = unitPriceOf(item);

            OrderReturnItem returnItem = new OrderReturnItem();
            returnItem.setOrderReturn(request);
            returnItem.setOrderItem(item);
            returnItem.setQuantity(wanted);
            returnItem.setUnitPrice(unitPrice);
            request.getItems().add(returnItem);

            provisional = provisional.add(unitPrice.multiply(BigDecimal.valueOf(wanted)));
        }

        OrderReturn saved = returnRepository.save(request);
        auditLogService.log("RETURN_REQUESTED", "OrderReturn", saved.getId(),
                "orderId=" + orderId + ", lines=" + lines.size() + ", provisional=" + provisional);
        return saved;
    }

    /**
     * The shop agrees: stock goes back, and a refund is opened.
     *
     * ONE TRANSACTION, and that is the point. Accepting the goods, restoring
     * the stock and owing the money are the same event. If the refund cannot
     * be opened, this rolls back rather than leaving a shop that has taken
     * items back with nothing recording what it owes.
     */
    @Transactional
    public OrderReturn approve(Long returnId, Long staffId) {

        OrderReturn request = lockedRequest(returnId);

        BigDecimal amount = BigDecimal.ZERO;
        for (OrderReturnItem item : request.getItems()) {
            // FROM THE STORED LINE PRICE, not from anything sent with the
            // approval. The admin screen shows this figure; it does not
            // choose it.
            amount = amount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);

        request.setStatus(OrderReturn.Status.APPROVED);
        request.setRefundAmount(amount);
        request.setDecidedAt(LocalDateTime.now());
        request.setDecidedBy(staffId == null ? null : customerRepository.findById(staffId).orElse(null));

        putTheStockBack(request);

        // THE REFUND IS OPENED THROUGH THE LEDGER, which enforces its own
        // invariant: the sum of a payment's refunds can never exceed what was
        // paid. Two returns on one order that together exceed the payment -
        // possible when a coupon made the order cheaper than its lines - are
        // refused there rather than here, in the one place that knows about
        // every refund on the payment.
        Payment payment = paymentRepository.findByOrderId(request.getOrder().getId()).orElse(null);
        if (payment != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            paymentService.refundPayment(request.getOrder().getId(), amount);
            Payment after = paymentRepository.findByOrderId(request.getOrder().getId()).orElse(null);
            if (after != null) {
                request.setRefundId(after.getRefundId());
            }
        } else {
            // An order with no payment row at all. The goods still come back
            // and the decision is still recorded; there is simply no money to
            // send, and inventing a refund would be worse than saying so.
            log.info("Return {} approved with no payment to refund (orderId={})",
                    returnId, request.getOrder().getId());
        }

        OrderReturn saved = returnRepository.save(request);
        auditLogService.log("RETURN_APPROVED", "OrderReturn", saved.getId(),
                "orderId=" + request.getOrder().getId() + ", amount=" + amount
                        + ", refundId=" + saved.getRefundId());
        return saved;
    }

    /**
     * The shop says no, and says why.
     *
     * THE NOTE IS REQUIRED. A refusal with no reason is how a customer
     * decides the shop is dishonest, and the shopkeeper who typed nothing
     * will not remember next week either.
     */
    @Transactional
    public OrderReturn reject(Long returnId, Long staffId, String note) {

        if (note == null || note.isBlank()) {
            throw new BadRequestException("Say why the return is refused - the customer will see it.");
        }

        OrderReturn request = lockedRequest(returnId);
        request.setStatus(OrderReturn.Status.REJECTED);
        request.setDecisionNote(trim(note, 500));
        request.setDecidedAt(LocalDateTime.now());
        request.setDecidedBy(staffId == null ? null : customerRepository.findById(staffId).orElse(null));

        OrderReturn saved = returnRepository.save(request);
        auditLogService.log("RETURN_REJECTED", "OrderReturn", saved.getId(),
                "orderId=" + request.getOrder().getId());
        return saved;
    }

    /** The customer changes their mind before anyone has looked. */
    @Transactional
    public OrderReturn cancel(Long returnId, Long customerId) {
        OrderReturn request = returnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found"));

        if (request.getCustomer() == null || !request.getCustomer().getId().equals(customerId)) {
            // Not "forbidden": a customer must not be able to learn that
            // somebody else's return exists by the shape of the refusal.
            throw new ResourceNotFoundException("Return not found");
        }
        if (request.getStatus() != OrderReturn.Status.REQUESTED) {
            throw new ConflictException("This return has already been decided.");
        }

        request.setStatus(OrderReturn.Status.CANCELLED);
        request.setDecidedAt(LocalDateTime.now());
        OrderReturn saved = returnRepository.save(request);
        auditLogService.log("RETURN_CANCELLED", "OrderReturn", saved.getId(),
                "orderId=" + request.getOrder().getId());
        return saved;
    }

    /**
     * My returns, and the shop's queue, mapped to DTOs INSIDE the transaction.
     *
     * WHY THESE LIVE HERE AND NOT IN THE CONTROLLER. OrderReturn's order,
     * items, variants and products are all lazy. Mapping them after the
     * request's transaction has closed throws LazyInitializationException,
     * which the error handler turns into a 500 - the shop's returns queue
     * failing with "an unexpected error occurred" and nothing to go on. It
     * did exactly that until a test asked whether staff could actually read
     * their own queue.
     *
     * N+1 WITHIN A PAGE, KNOWINGLY. Each row pulls its order and its lines.
     * A fetch join would fix that but Hibernate cannot paginate a joined
     * collection in SQL - it pulls the whole result set and pages it in
     * memory, which is worse on the table that grows forever. Twenty rows a
     * page on a queue a shopkeeper works through by hand is the wrong thing
     * to optimise; if this ever becomes a hot path, page the ids first and
     * fetch the collection for that page.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ReturnResponse> mine(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return returnRepository.forCustomer(customerId, pageable).map(ReturnResponse::from);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ReturnResponse> awaitingDecision(
            org.springframework.data.domain.Pageable pageable) {
        return returnRepository.awaitingDecision(pageable).map(ReturnResponse::from);
    }

    /** How much of each line is still returnable, for the app to draw the form. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> returnableLines(Long customerId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        Map<Long, Integer> remaining = new LinkedHashMap<>();
        for (OrderItem item : orderItemRepository.findByOrderId(orderId)) {
            int bought = item.getQuantity() == null ? 0 : item.getQuantity();
            int left = bought - returnRepository.unitsAlreadyClaimedFor(item.getId());
            remaining.put(item.getId(), Math.max(0, left));
        }
        return remaining;
    }

    // ------------------------------------------------------------- internals

    private OrderReturn lockedRequest(Long returnId) {
        OrderReturn request = returnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("Return not found"));
        if (request.getStatus() != OrderReturn.Status.REQUESTED) {
            // Two admins pressing Approve at the same moment: the row lock
            // makes the second wait, and this is what it sees when it wakes.
            // Without it both would refund the same return.
            throw new ConflictException("This return has already been decided.");
        }
        return request;
    }

    /**
     * Only a delivered order, only inside the window, and only once the money
     * has settled.
     */
    private void requireReturnable(Order order) {
        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw new ConflictException(
                    "Only a delivered order can be returned. If it has not arrived yet, cancel it instead.");
        }

        LocalDateTime deliveredAt = deliveryRepository.findByOrderId(order.getId())
                .map(Delivery::getDeliveredAt)
                .orElse(null);

        if (deliveredAt == null) {
            // The order says DELIVERED but nothing recorded when. Refusing
            // would punish the customer for the shop's missing timestamp, so
            // the window is treated as open and a person decides.
            log.info("Order {} is DELIVERED with no delivery timestamp; returns window not enforced.",
                    order.getId());
            return;
        }

        Duration since = Duration.between(deliveredAt, LocalDateTime.now());
        if (since.toDays() > windowDays) {
            throw new ConflictException(
                    "Returns close " + windowDays + " days after delivery.");
        }
    }

    /**
     * The stock goes back on the shelf when the shop accepts the goods.
     *
     * NOT WHEN THE RETURN IS REQUESTED. The items are still in the customer's
     * house then, and counting them as sellable is how a shop promises stock
     * it does not have.
     */
    private void putTheStockBack(OrderReturn request) {

        // SORTED BY VARIANT ID BEFORE TAKING ANY INVENTORY LOCK. This is the
        // application-wide lock order that checkout and cancellation already
        // follow, and it is not optional: a return restoring variants [5, 3]
        // while a checkout holds a cart of [3, 5] deadlocks - each waits for
        // the row the other holds. Postgres kills one of them, so it surfaces
        // as a random failed checkout under load and never reproduces on
        // demand. Only a globally consistent order prevents it, so every path
        // that locks inventory sorts identically: ascending variant id.
        var lines = request.getItems().stream()
                .filter(item -> item.getOrderItem() != null
                        && item.getOrderItem().getProductVariant() != null)
                .sorted(java.util.Comparator.comparing(
                        item -> item.getOrderItem().getProductVariant().getId()))
                .toList();

        for (OrderReturnItem item : lines) {
            ProductVariant variant = item.getOrderItem().getProductVariant();
            Inventory inventory = inventoryService.getByProductVariantForUpdate(variant.getId());
            if (inventory == null || item.getQuantity() == null) {
                continue;
            }
            inventory.setStock(inventory.getStock() + item.getQuantity());
            // Through the service, not the repository, so the non-negative
            // stock guard runs on this path too - even though it only adds.
            inventoryService.save(inventory);
        }
    }

    /**
     * What one unit of a line was actually charged.
     *
     * FROM totalPrice WHERE IT EXISTS, because that is what the customer was
     * billed after any per-line discount; price alone is the list price and
     * refunding it would hand back more than was taken. Falls back to price
     * only when there is no line total to divide.
     */
    private static BigDecimal unitPriceOf(OrderItem item) {
        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        BigDecimal total = item.getTotalPrice();
        if (total != null && quantity > 0) {
            return total.divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP);
        }
        BigDecimal price = item.getPrice();
        return price == null ? BigDecimal.ZERO : price.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
