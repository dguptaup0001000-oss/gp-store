package com.gpstore.delivery;

import com.gpstore.enums.DeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule that stops an order being marked delivered off the packing bench.
 *
 * WHAT THIS IS GUARDING. DeliveryService.updateDeliveryStatus used to do
 *
 *     delivery.setDeliveryStatus(status);
 *
 * with `status` arriving as a query parameter from a phone. Nothing checked
 * it against anything, and the DELIVERED branch fires on the string alone -
 * so a delivery partner could send DELIVERED for an order that had never been
 * picked up. That stamps deliveredAt, tells the customer it arrived, and
 * completes the COD payment for cash nobody collected.
 *
 * A vocabulary check alone would not have stopped it. Knowing DELIVERED is a
 * real word is not the property that matters; knowing it cannot follow
 * ASSIGNED is.
 */
@DisplayName("Delivery status transitions")
class DeliveryStatusTransitionsTest {

    // ------------------------------------------------------- the actual bug

    @Test
    @DisplayName("an order that was never picked up cannot be marked delivered")
    void deliveredCannotBeReachedFromTheBench() {
        assertFalse(DeliveryStatusTransitions.isAllowed(DeliveryStatus.ASSIGNED, DeliveryStatus.DELIVERED),
                "This is the bug this whole type exists for: DELIVERED from ASSIGNED stamps a delivery "
                        + "time, notifies the customer, and settles a COD payment for money nobody took.");
        assertFalse(DeliveryStatusTransitions.isAllowed(DeliveryStatus.PACKED, DeliveryStatus.DELIVERED));
        assertFalse(DeliveryStatusTransitions.isAllowed(DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERED),
                "Even holding the carton is not delivering it - it has to go out first.");
    }

    @Test
    @DisplayName("the ordinary day is allowed end to end")
    void theHappyPathWorks() {
        DeliveryStatus[] day = {
                DeliveryStatus.ASSIGNED, DeliveryStatus.PACKED, DeliveryStatus.PICKED_UP,
                DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.DELIVERED};

        for (int i = 0; i < day.length - 1; i++) {
            assertTrue(DeliveryStatusTransitions.isAllowed(day[i], day[i + 1]),
                    day[i] + " -> " + day[i + 1] + " is the normal flow and must be allowed.");
        }
    }

    // ------------------------------------------------------------ the edges

    @Test
    @DisplayName("a failed attempt can be tried again")
    void failedGoesBackOut() {
        assertTrue(DeliveryStatusTransitions.isAllowed(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.FAILED));
        // Nobody was home at four; somebody is home at seven. Making FAILED
        // terminal would mean the only way to retry is editing a database row.
        assertTrue(DeliveryStatusTransitions.isAllowed(DeliveryStatus.FAILED, DeliveryStatus.OUT_FOR_DELIVERY));
        assertTrue(DeliveryStatusTransitions.isAllowed(DeliveryStatus.FAILED, DeliveryStatus.RETURNED));
    }

    @Test
    @DisplayName("nothing moves on from a finished delivery")
    void terminalStatesAreTerminal() {
        for (DeliveryStatus terminal : List.of(
                DeliveryStatus.DELIVERED, DeliveryStatus.RETURNED, DeliveryStatus.CANCELLED)) {

            assertTrue(terminal.isTerminal());
            assertEquals(List.of(), DeliveryStatusTransitions.nextFrom(terminal),
                    terminal + " must offer no onward moves.");

            for (DeliveryStatus other : DeliveryStatus.values()) {
                if (other == terminal) {
                    continue;
                }
                assertFalse(DeliveryStatusTransitions.isAllowed(terminal, other),
                        terminal + " -> " + other + " must be refused.");
            }
        }
    }

    @Test
    @DisplayName("a delivered order cannot be un-delivered")
    void deliveredCannotBeCancelled() {
        // The one that would be tempting to allow. It must not be: the goods
        // changed hands, and the process for undoing that is a return, which
        // is a different thing with different money attached.
        assertFalse(DeliveryStatusTransitions.isAllowed(DeliveryStatus.DELIVERED, DeliveryStatus.CANCELLED));
    }

    @Test
    @DisplayName("a customer can cancel at any point before the goods change hands")
    void cancellationIsReachableUntilItIsNot() {
        for (DeliveryStatus live : List.of(
                DeliveryStatus.ASSIGNED, DeliveryStatus.PACKED,
                DeliveryStatus.PICKED_UP, DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.FAILED)) {
            assertTrue(DeliveryStatusTransitions.isAllowed(live, DeliveryStatus.CANCELLED),
                    "A customer calling to cancel while the delivery is " + live + " is ordinary.");
        }
    }

    @Test
    @DisplayName("re-sending the status it is already in changes nothing and is not an error")
    void repeatingAStatusIsAllowed() {
        // A worker on a bad connection taps "Out for delivery", sees nothing
        // happen, taps again. The second request must not be an error screen -
        // and the service returns early rather than re-running the side
        // effects, which is what stops a double-tap stamping deliveredAt twice.
        for (DeliveryStatus status : DeliveryStatus.values()) {
            assertTrue(DeliveryStatusTransitions.isAllowed(status, status),
                    status + " repeated must be accepted as a no-op.");
        }
    }

    @Test
    @DisplayName("a delivery with no status yet is treated as freshly assigned")
    void nullIsNotStuck() {
        // Rows written before this type existed. They must be able to move,
        // or every delivery already in the database becomes unworkable.
        assertTrue(DeliveryStatusTransitions.isAllowed(null, DeliveryStatus.PACKED));
        assertFalse(DeliveryStatusTransitions.isAllowed(null, DeliveryStatus.DELIVERED),
                "An unknown starting state must not be a shortcut to the end.");
    }

    // ------------------------------------------------------------- parsing

    @Test
    @DisplayName("nonsense is not a delivery status")
    void garbageIsRejected() {
        for (String rubbish : new String[]{"BANANA", "", "   ", "DELIVERED_MAYBE", "12", null}) {
            assertTrue(DeliveryStatus.parse(rubbish).isEmpty(),
                    "\"" + rubbish + "\" must not parse to a status.");
        }
    }

    @Test
    @DisplayName("case and stray whitespace are tolerated, not rejected")
    void realisticClientInputStillParses() {
        // Rejecting "delivered" from a client that lower-cased it would be
        // calling a formatting difference a security boundary.
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatus.parse("delivered").orElseThrow());
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatus.parse("  DELIVERED  ").orElseThrow());
        assertEquals(DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.parse("Out_For_Delivery").orElseThrow());
    }

    // -------------------------------------------------- what the app is told

    @Test
    @DisplayName("every state offers exactly the moves that are allowed from it")
    void nextFromAgreesWithIsAllowed() {
        // The app draws one button per entry in nextFrom and knows nothing
        // else. If these two ever disagreed, a worker would be shown a button
        // the server refuses - or denied one it would have accepted.
        for (DeliveryStatus from : DeliveryStatus.values()) {
            List<DeliveryStatus> offered = DeliveryStatusTransitions.nextFrom(from);

            for (DeliveryStatus to : DeliveryStatus.values()) {
                if (to == from) {
                    continue;
                }
                assertEquals(offered.contains(to), DeliveryStatusTransitions.isAllowed(from, to),
                        from + " -> " + to + ": nextFrom and isAllowed disagree.");
            }
        }
    }

    @Test
    @DisplayName("a refusal says what would have been allowed")
    void refusalsAreActionable() {
        String message = DeliveryStatusTransitions.refusalMessage(
                DeliveryStatus.ASSIGNED, DeliveryStatus.DELIVERED);

        assertTrue(message.contains("ASSIGNED"), "The message must say where the delivery actually is.");
        assertTrue(message.contains("PACKED"),
                "\"Not allowed\" leaves somebody standing at a door with no idea what to press. "
                        + "The message must name the moves that would work.");
    }
}
