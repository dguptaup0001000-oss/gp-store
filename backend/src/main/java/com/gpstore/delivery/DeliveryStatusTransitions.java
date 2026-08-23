package com.gpstore.delivery;

import com.gpstore.enums.DeliveryStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which delivery status may follow which, and nothing else.
 *
 * THE ORDER IS THE POINT. A vocabulary check alone - "is this one of the eight
 * words" - would still have let a worker send DELIVERED for an order still on
 * the packing bench, which stamps deliveredAt, tells the customer it arrived
 * and closes the COD payment for cash nobody collected. What stops that is not
 * knowing the word; it is knowing the word cannot follow ASSIGNED.
 *
 * THE SHAPE, and why each edge is here:
 *
 *     ASSIGNED ──► PACKED ──► PICKED_UP ──► OUT_FOR_DELIVERY ──► DELIVERED
 *         │           │            │                │
 *         │           │            │                ▼
 *         │           │            │             FAILED ──► OUT_FOR_DELIVERY
 *         │           │            │                │           (retry)
 *         │           │            │                ▼
 *         └───────────┴────────────┴──────────► CANCELLED    RETURNED
 *
 * FAILED GOES BACK OUT. A missed delivery is the ordinary case, not an
 * ending: nobody was home at four, somebody is home at seven. Making FAILED
 * terminal would mean the only way to retry is an administrator editing a row.
 *
 * CANCELLED IS REACHABLE FROM ANYWHERE THAT IS NOT FINISHED, because a
 * customer can call and cancel at any point before the goods change hands.
 * It is NOT reachable from DELIVERED - once handed over, the thing that
 * happened cannot be un-happened, and the process for that is a return.
 *
 * A STATUS MAY REPEAT ITSELF. Setting OUT_FOR_DELIVERY when already
 * OUT_FOR_DELIVERY is allowed and does nothing - a double-tapped button on a
 * bad connection is not an error worth showing a worker, and the alternative
 * is teaching the app to tell "my retry succeeded twice" apart from "I am
 * confused", which it cannot do from the client side.
 */
public final class DeliveryStatusTransitions {

    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED =
            new EnumMap<>(DeliveryStatus.class);

    static {
        ALLOWED.put(DeliveryStatus.ASSIGNED, EnumSet.of(
                DeliveryStatus.PACKED,
                DeliveryStatus.CANCELLED));

        ALLOWED.put(DeliveryStatus.PACKED, EnumSet.of(
                DeliveryStatus.PICKED_UP,
                DeliveryStatus.CANCELLED));

        ALLOWED.put(DeliveryStatus.PICKED_UP, EnumSet.of(
                DeliveryStatus.OUT_FOR_DELIVERY,
                // Straight back to the shop without ever leaving it: the
                // customer cancelled while the worker was loading the bike.
                DeliveryStatus.RETURNED,
                DeliveryStatus.CANCELLED));

        ALLOWED.put(DeliveryStatus.OUT_FOR_DELIVERY, EnumSet.of(
                DeliveryStatus.DELIVERED,
                DeliveryStatus.FAILED,
                DeliveryStatus.CANCELLED));

        ALLOWED.put(DeliveryStatus.FAILED, EnumSet.of(
                // The retry.
                DeliveryStatus.OUT_FOR_DELIVERY,
                DeliveryStatus.RETURNED,
                DeliveryStatus.CANCELLED));

        // Terminal. Deliberately present as empty sets rather than absent, so
        // a missing key is a programming error rather than a silent "nothing
        // is allowed from here".
        ALLOWED.put(DeliveryStatus.DELIVERED, EnumSet.noneOf(DeliveryStatus.class));
        ALLOWED.put(DeliveryStatus.RETURNED, EnumSet.noneOf(DeliveryStatus.class));
        ALLOWED.put(DeliveryStatus.CANCELLED, EnumSet.noneOf(DeliveryStatus.class));
    }

    private DeliveryStatusTransitions() {
    }

    /**
     * The states this delivery may move to next, in the order a worker would
     * meet them.
     *
     * SENT TO THE APP, which is the reason this is a List and not the Set the
     * map holds. The worker app draws one button per entry and knows nothing
     * about the rules itself - so the rules live in exactly one place, the
     * app cannot offer an action the server would refuse, and a phone running
     * last month's build cannot invent a transition that has since been
     * removed. The server still re-checks on the way in; this only decides
     * what is worth showing.
     */
    public static List<DeliveryStatus> nextFrom(DeliveryStatus current) {
        if (current == null) {
            // No status at all is a row written before this type existed.
            // Treat it as freshly assigned rather than as stuck.
            return List.of(DeliveryStatus.PACKED, DeliveryStatus.CANCELLED);
        }
        Set<DeliveryStatus> allowed = ALLOWED.get(current);
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        // Enum declaration order, which is the order the day happens in.
        return allowed.stream().sorted().toList();
    }

    /**
     * @return true when moving from {@code current} to {@code next} is legal.
     */
    public static boolean isAllowed(DeliveryStatus current, DeliveryStatus next) {
        if (next == null) {
            return false;
        }
        if (current == null) {
            return nextFrom(null).contains(next);
        }
        // Re-asserting the state you are already in. Always fine, never a
        // change - see the class comment on double-tapped buttons.
        if (current == next) {
            return true;
        }
        Set<DeliveryStatus> allowed = ALLOWED.get(current);
        return allowed != null && allowed.contains(next);
    }

    /**
     * Why a refusal was a refusal, in a sentence a worker can act on.
     *
     * Names the current state, because "that is not allowed" leaves somebody
     * standing at a door with no idea what to press instead.
     */
    public static String refusalMessage(DeliveryStatus current, DeliveryStatus next) {
        String from = current == null ? "not yet started" : current.name();
        List<DeliveryStatus> options = nextFrom(current);
        if (options.isEmpty()) {
            return "This delivery is already " + from + " and cannot be changed.";
        }
        return "This delivery is " + from + ", so it cannot go straight to " + next.name()
                + ". Allowed next: " + String.join(", ", options.stream().map(Enum::name).toList()) + ".";
    }
}
