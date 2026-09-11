package com.gpstore.order;

import com.gpstore.entity.Order;
import com.gpstore.enums.OrderActor;
import com.gpstore.enums.OrderStatus;
import com.gpstore.exception.ConflictException;

/**
 * THE ONE PLACE AN ORDER'S STATUS IS ALLOWED TO CHANGE.
 *
 * <p>WHY THIS EXISTS. {@link OrderLifecycle} already held the table of what
 * may follow what and who may do it, and {@code OrderService} consulted it
 * properly - but three other services wrote {@code order.setOrderStatus(...)}
 * directly and never asked. A table nothing is obliged to consult is
 * documentation, not enforcement, and the gap was reachable:
 *
 * <ul>
 *   <li>a delivery marked DELIVERED forced its order to DELIVERED even when
 *       the order had already been CANCELLED and refunded - resurrecting a
 *       dead order into a delivered one, with the customer's money already
 *       returned;</li>
 *   <li>the abandoned-payment sweep cancelled any order that was not already
 *       CANCELLED or DELIVERED, which includes COMPLETED and REJECTED ones -
 *       both terminal, neither cancellable.</li>
 * </ul>
 *
 * <p>IT IS A FUNCTION, NOT A SERVICE, for the same reason OrderLifecycle is:
 * whether a move is legal is a pure question of (from, to, who), and the
 * answer must not depend on which bean happened to ask.
 *
 * <p>NOT A SECOND COPY OF THE RULES. Every decision here is delegated to
 * OrderLifecycle. This adds the refusal, the wording and the no-op, and
 * nothing else - if the two ever disagreed, the table would be the one that
 * was right.
 */
public final class OrderStatusChange {

    private OrderStatusChange() {
    }

    /**
     * Moves the order, or refuses and says why.
     *
     * <p>RE-ASSERTING THE SAME STATE IS A NO-OP, not a refusal. A retried
     * request, a duplicate webhook and a double-tapped button all arrive as
     * "set it to what it already is", and throwing at them would turn
     * idempotent callers into failing ones.
     *
     * @throws ConflictException when the move is not one this order can make,
     *                           or not one this actor may make
     */
    public static void move(Order order, OrderStatus to, OrderActor actor) {
        OrderStatus from = order.getOrderStatus();
        if (from == to) {
            return;
        }
        if (!OrderLifecycle.allows(from, to, actor)) {
            throw new ConflictException(refusal(from, to, actor));
        }
        order.setOrderStatus(to);
    }

    /**
     * Whether the move would be accepted, for callers that have something
     * better to do than fail.
     *
     * <p>The abandoned-payment sweep is the case this exists for: it runs over
     * a batch, and one order it may not touch is a reason to leave that order
     * alone, not to abandon the batch.
     */
    public static boolean canMove(Order order, OrderStatus to, OrderActor actor) {
        OrderStatus from = order.getOrderStatus();
        return from == to || OrderLifecycle.allows(from, to, actor);
    }

    /**
     * Why not, in words a person reading a log or an error can act on.
     *
     * <p>SEPARATES THE TWO REFUSALS, because they call for different things.
     * "An order cannot go there" is a bug or a race; "you may not take it
     * there" is an authorisation answer and the caller needs to know which
     * they got.
     */
    private static String refusal(OrderStatus from, OrderStatus to, OrderActor actor) {
        String where = "from " + name(from) + " to " + name(to);
        if (!OrderLifecycle.allows(from, to)) {
            return "An order cannot move " + where + ".";
        }
        return "A " + (actor == null ? "caller" : actor.name().toLowerCase())
                + " may not move an order " + where + ".";
    }

    private static String name(OrderStatus status) {
        return status == null ? "no status" : status.name();
    }
}
