package com.gpstore.order;

import com.gpstore.enums.OrderActor;
import com.gpstore.enums.OrderStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WHERE AN ORDER MAY GO NEXT, AND WHO MAY TAKE IT THERE.
 *
 * <p>WHY THIS IS A TABLE AND NOT A CHAIN OF ORs. The rules used to live as a
 * fourteen-clause boolean expression inside OrderService.updateOrderStatus.
 * It was correct, and it was unreadable: nobody could answer "what can a
 * PACKED order do next" without re-deriving it, the merchant app had no way
 * to ask, and adding a state meant appending clauses and hoping. Part 3 §1
 * asks for clear transitions and for invalid ones to be prevented; a table is
 * what "clear" means when a person has to check the code against a diagram.
 *
 * <p>NO SPRING, NO DATABASE, NO CLOCK - the same reason DeliverySchedule is
 * split out. Every question here is a pure function of (from, to, who), so
 * the cases are tested by calling a method rather than by standing up a
 * container, and the service above keeps only the plumbing.
 *
 * <p>TWO QUESTIONS, NOT ONE. "Is this transition possible" and "may THIS
 * person make it" are different, and collapsing them is how a worker ends up
 * able to confirm an order or a customer able to mark one delivered. The
 * actor rules are the second half and they are enforced, not advisory.
 *
 * <p>EVERY TRANSITION THAT WORKED BEFORE STILL WORKS. This table was
 * transcribed from the boolean chain it replaces, clause by clause, and the
 * additions are only the ones Part 3 names. PACKED and READY_TO_DISPATCH
 * still mean the same thing operationally and still reach each other in both
 * directions - see OrderStatus.PACKED for why the older state stays.
 */
public final class OrderLifecycle {

    private OrderLifecycle() {
    }

    /** Terminal: nothing moves out of these. */
    private static final Set<OrderStatus> TERMINAL = EnumSet.of(
            OrderStatus.DELIVERED, OrderStatus.CANCELLED,
            OrderStatus.REJECTED, OrderStatus.COMPLETED);

    /**
     * The states in which the shop has TAKEN THE ORDER ON.
     *
     * <p>§2's line, drawn once. From CONFIRMED onwards the merchant owes the
     * order, and every rule that follows from that - a pause not touching the
     * board, rejection no longer being available, fault mattering to the
     * charge - reads this rather than listing states again.
     */
    private static final Set<OrderStatus> ACCEPTED = EnumSet.of(
            OrderStatus.CONFIRMED, OrderStatus.PACKING, OrderStatus.PACKED,
            OrderStatus.READY_TO_DISPATCH, OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED, OrderStatus.COMPLETED);

    /**
     * The stages at which a customer may still call an order off themselves.
     *
     * <p>Before the shop has started putting the packet together. Past that,
     * cancelling is a conversation with the shop rather than a button - which
     * is what §9 means by "before the merchant reaches the relevant
     * preparation stage".
     */
    public static final Set<OrderStatus> CUSTOMER_MAY_CANCEL = EnumSet.of(
            OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED);

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED =
            new EnumMap<>(OrderStatus.class);

    /** Who may make a given move. Absent means "nobody but the platform". */
    private static final Map<String, Set<OrderActor>> ACTORS = new java.util.HashMap<>();

    static {
        // ---------------------------------------------- the ordinary journey
        allow(OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED,
                OrderActor.MERCHANT, OrderActor.PLATFORM);
        allow(OrderStatus.CONFIRMED, OrderStatus.PACKING,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.PACKING, OrderStatus.READY_TO_DISPATCH,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.READY_TO_DISPATCH, OrderStatus.OUT_FOR_DELIVERY,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);

        // PACKED sits beside READY_TO_DISPATCH rather than replacing it: it is
        // what a worker's QR scan writes, the older state is still reachable
        // from the admin dropdown and still on live orders, and deleting a
        // state production rows hold breaks every order mid-flight.
        allow(OrderStatus.CONFIRMED, OrderStatus.PACKED,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.PACKING, OrderStatus.PACKED,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.READY_TO_DISPATCH, OrderStatus.PACKED,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.PACKED, OrderStatus.READY_TO_DISPATCH,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);

        // ------------------------------------------------ finishing with it
        //
        // Nothing moves an order here automatically YET. The window after
        // which an order stops being returnable is a policy nobody has chosen,
        // and inventing one would be closing customers' claims on a number
        // this code made up - so the transition exists and a person makes it.
        allow(OrderStatus.DELIVERED, OrderStatus.COMPLETED,
                OrderActor.MERCHANT, OrderActor.PLATFORM, OrderActor.SYSTEM);

        // ------------------------------------------------- when it goes wrong
        //
        // The round came back. Not the end of the order: the usual outcome is
        // that it goes out again, which is why this is not CANCELLED.
        allow(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.DELIVERY_FAILED, OrderStatus.OUT_FOR_DELIVERY,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);
        allow(OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED,
                OrderActor.MERCHANT, OrderActor.WORKER, OrderActor.PLATFORM);

        // REFUSED BEFORE ACCEPTING, and only before. §2: once a shop has said
        // yes it owes the order, so there is deliberately no CONFIRMED ->
        // REJECTED edge. A shop that cannot fulfil an accepted order cancels
        // it, at MERCHANT fault, and the customer is not charged (§12).
        allow(OrderStatus.PENDING_CONFIRMATION, OrderStatus.REJECTED,
                OrderActor.MERCHANT, OrderActor.PLATFORM);

        // ---------------------------------------------------- calling it off
        //
        // Reachable from every state that is not already over. WHO may do it
        // is where the real rule lives: a customer may call off an order the
        // shop has not started preparing, and after that it is the shop's or
        // the platform's decision (§9). The stage limit for customers is
        // CUSTOMER_MAY_CANCEL below.
        for (OrderStatus from : EnumSet.of(OrderStatus.PENDING_CONFIRMATION,
                OrderStatus.CONFIRMED, OrderStatus.PACKING, OrderStatus.PACKED,
                OrderStatus.READY_TO_DISPATCH, OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERY_FAILED)) {
            allow(from, OrderStatus.CANCELLED,
                    OrderActor.MERCHANT, OrderActor.PLATFORM, OrderActor.SYSTEM);
        }
        // The customer's own reach, kept to the stages before the shop has
        // started work. §9 lets a merchant's policy extend this; it may never
        // extend past what the table allows.
        for (OrderStatus from : CUSTOMER_MAY_CANCEL) {
            ACTORS.get(key(from, OrderStatus.CANCELLED)).add(OrderActor.CUSTOMER);
        }
    }

    private static void allow(OrderStatus from, OrderStatus to, OrderActor... actors) {
        ALLOWED.computeIfAbsent(from, f -> EnumSet.noneOf(OrderStatus.class)).add(to);
        ACTORS.computeIfAbsent(key(from, to), k -> EnumSet.noneOf(OrderActor.class))
                .addAll(java.util.Arrays.asList(actors));
    }

    private static String key(OrderStatus from, OrderStatus to) {
        return from.name() + ">" + to.name();
    }

    /** Whether the order can move from one state to the other at all. */
    public static boolean allows(OrderStatus from, OrderStatus to) {
        return from != null && to != null
                && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Whether THIS actor may make that move.
     *
     * <p>The second question, and the one that stops a worker confirming an
     * order or a customer marking one delivered.
     */
    public static boolean allows(OrderStatus from, OrderStatus to, OrderActor actor) {
        return allows(from, to)
                && actor != null
                && ACTORS.getOrDefault(key(from, to), Set.of()).contains(actor);
    }

    /** Everywhere an order in this state can go. For the merchant's screen. */
    public static Set<OrderStatus> nextFrom(OrderStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }

    /** Everywhere THIS actor can take it. For the merchant's and worker's screens. */
    public static Set<OrderStatus> nextFrom(OrderStatus from, OrderActor actor) {
        Set<OrderStatus> reachable = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus to : ALLOWED.getOrDefault(from, Set.of())) {
            if (allows(from, to, actor)) {
                reachable.add(to);
            }
        }
        return Set.copyOf(reachable);
    }

    /** Nothing moves out of a terminal state. */
    public static boolean isTerminal(OrderStatus status) {
        return status != null && TERMINAL.contains(status);
    }

    /**
     * Whether the shop has taken this order on (§2).
     *
     * <p>From here the merchant owes it: closing the shop, pausing orders and
     * stopping for the day all leave it exactly where it is.
     */
    public static boolean isAccepted(OrderStatus status) {
        return status != null && ACCEPTED.contains(status);
    }

    /** Whether a customer may still call it off themselves (§9). */
    public static boolean customerMayCancel(OrderStatus status) {
        return status != null && CUSTOMER_MAY_CANCEL.contains(status);
    }
}
