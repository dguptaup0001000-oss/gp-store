package com.gpstore.ordergroup;

import com.gpstore.entity.Order;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.OrderRepository;
import com.gpstore.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What the customer thinks they placed, and what they can still do to it.
 *
 * THE GROUP IS A VIEW, NOT A SECOND ORDER. Nothing here duplicates order
 * logic: cancelling a group is cancelling each shop's order through the same
 * OrderService.cancelOrder that a single cancellation uses, and the answer is
 * per shop because the outcome genuinely is - one kirana may still be able to
 * cancel while the other's rider is already at the door.
 *
 * SO IT IS NOT ALL-OR-NOTHING, and that is deliberate rather than a
 * simplification. Refusing to cancel anything because one shop has already
 * dispatched would leave the customer paying for the half they can still
 * stop; cancelling everything regardless would cancel an order that is
 * physically on its way. Each shop answers for itself and the customer is told
 * which did what.
 */
@Service
public class OrderGroupService {

    private static final Logger log = LoggerFactory.getLogger(OrderGroupService.class);

    private final OrderGroupRepository groups;
    private final OrderRepository orders;
    private final OrderService orderService;

    public OrderGroupService(OrderGroupRepository groups, OrderRepository orders,
                             OrderService orderService) {
        this.groups = groups;
        this.orders = orders;
        this.orderService = orderService;
    }

    /** One shop's part of a checkout, as the customer sees it. */
    public record ShopOrderView(Long orderId, String orderNumber, Long shopId, String shopStatus,
                                String paymentStatus, BigDecimal totalAmount, BigDecimal deliveryFee,
                                boolean cancellable) {}

    public record GroupView(Long id, String groupNumber, BigDecimal totalAmount, int shopCount,
                            java.time.LocalDateTime placedAt, List<ShopOrderView> shopOrders) {}

    /** The outcome of trying to cancel one shop's order. */
    public record CancelOutcome(Long orderId, Long shopId, boolean cancelled, String reason) {}

    public record CancelResult(Long groupId, String groupNumber, List<CancelOutcome> outcomes) {

        public boolean allCancelled() {
            return outcomes.stream().allMatch(CancelOutcome::cancelled);
        }
    }

    @Transactional(readOnly = true)
    public List<GroupView> myCheckouts(Long customerId) {
        List<GroupView> views = new ArrayList<>();
        for (OrderGroup group : groups.findByCustomerIdOrderByIdDesc(customerId)) {
            views.add(viewOf(group));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public GroupView myCheckout(Long customerId, Long groupId) {
        return viewOf(ownedGroup(customerId, groupId));
    }

    /**
     * Cancels every shop's order in a checkout that can still be cancelled.
     *
     * ONE SHOP FAILING DOES NOT STOP THE OTHERS. Each is attempted on its own
     * and its outcome recorded - a group cancel that gave up at the first
     * refusal would leave the customer paying for a half they could have
     * stopped, and there would be no way to tell from the response which half
     * that was.
     */
    /**
     * DELIBERATELY NOT @Transactional, and this is the whole reason the method
     * works.
     *
     * OrderService.cancelOrder is transactional. Wrapping the loop in one
     * transaction makes every call join it - so the first shop that refuses
     * marks the SHARED transaction rollback-only, and the cancellations that
     * had already succeeded are silently undone at commit. The customer would
     * be told two shops were cancelled and find that neither was.
     *
     * Without the wrapper each shop's cancellation is its own transaction:
     * it commits or rolls back on its own, exactly as it does when a customer
     * cancels that one order by itself. Which is the requirement stated
     * plainly - each shop order has an independent lifecycle - rather than a
     * convenience.
     */
    public CancelResult cancelWholeCheckout(Long customerId, Long groupId) {
        OrderGroup group = ownedGroup(customerId, groupId);
        List<CancelOutcome> outcomes = new ArrayList<>();

        for (Order order : ordersIn(group)) {
            if (order.getOrderStatus() == OrderStatus.CANCELLED) {
                outcomes.add(new CancelOutcome(order.getId(), order.getShopId(), true,
                        "Already cancelled"));
                continue;
            }
            try {
                // THE SAME CANCELLATION EVERY SINGLE ORDER GETS. Inventory
                // restore, refund obligation, notification, audit - none of it
                // is re-implemented here, so a group cancel can never behave
                // differently from cancelling the same orders one at a time.
                orderService.cancelOrder(order.getId(), customerId, false);
                outcomes.add(new CancelOutcome(order.getId(), order.getShopId(), true, null));
            } catch (RuntimeException refused) {
                // Expected, not exceptional: an order already out for delivery
                // cannot be cancelled, and the customer needs to be told which
                // one and why rather than shown a failed request.
                log.info("Group {} - shop order {} could not be cancelled: {}",
                        group.getGroupNumber(), order.getId(), refused.getMessage());
                outcomes.add(new CancelOutcome(order.getId(), order.getShopId(), false,
                        refused.getMessage()));
            }
        }
        return new CancelResult(group.getId(), group.getGroupNumber(), outcomes);
    }

    /**
     * The group, if it is this customer's.
     *
     * OWNERSHIP IS THE WHOLE CHECK HERE. A group has no shop, so the tenant
     * filter has nothing to say about it; what stops one customer opening
     * another's checkout is that the row names its customer and this compares
     * it to the id on the token. Answering "not found" rather than "forbidden"
     * keeps a guessed id from confirming that somebody else's checkout exists.
     */
    private OrderGroup ownedGroup(Long customerId, Long groupId) {
        OrderGroup group = groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (customerId == null || !customerId.equals(group.getCustomerId())) {
            throw new ResourceNotFoundException("Order not found");
        }
        return group;
    }

    private List<Order> ordersIn(OrderGroup group) {
        return orders.findByOrderGroupIdOrderByShopIdAsc(group.getId());
    }

    private GroupView viewOf(OrderGroup group) {
        List<ShopOrderView> shopOrders = new ArrayList<>();
        for (Order order : ordersIn(group)) {
            shopOrders.add(new ShopOrderView(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getShopId(),
                    order.getOrderStatus() == null ? null : order.getOrderStatus().name(),
                    order.getPaymentStatus() == null ? null : order.getPaymentStatus().name(),
                    order.getTotalAmount(),
                    order.getDeliveryFee(),
                    isCancellable(order)));
        }
        return new GroupView(group.getId(), group.getGroupNumber(), group.getTotalAmount(),
                group.getShopCount() == null ? shopOrders.size() : group.getShopCount(),
                group.getCreatedAt(), shopOrders);
    }

    /**
     * Whether the customer can still stop this one.
     *
     * A HINT FOR A BUTTON, NOT THE RULE. OrderService.cancelOrder decides, and
     * it decides again when the button is pressed - this only exists so the
     * screen does not offer an action that will be refused a second later.
     */
    private static boolean isCancellable(Order order) {
        OrderStatus status = order.getOrderStatus();
        return status == OrderStatus.PENDING_CONFIRMATION
                || status == OrderStatus.CONFIRMED
                || status == OrderStatus.PACKING;
    }
}
