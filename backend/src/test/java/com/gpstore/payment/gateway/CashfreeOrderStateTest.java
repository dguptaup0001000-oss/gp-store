package com.gpstore.payment.gateway;

import com.gpstore.payment.gateway.PaymentGateway.GatewayOrderStatus.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cashfree's order_status vocabulary, normalised.
 *
 * In the gateway package rather than alongside the service tests so
 * mapState can stay package-private - the mapping is an implementation
 * detail of this adapter, not part of the interface the rest of the
 * application uses.
 */
class CashfreeOrderStateTest {

    @Test
    @DisplayName("PAID is the only state that means the money arrived")
    void paidIsPaid() {
        assertEquals(State.PAID, CashfreeGateway.mapState("PAID"));
        assertEquals(State.PAID, CashfreeGateway.mapState("paid"));
    }

    @Test
    @DisplayName("ACTIVE means still in progress, not failed")
    void activeIsNotFailure() {
        // A live session is a customer mid-payment. Treating it as failure
        // would cancel orders out from under people still paying for them.
        assertEquals(State.ACTIVE, CashfreeGateway.mapState("ACTIVE"));
    }

    @Test
    @DisplayName("Expiry and termination are distinguished from each other")
    void terminalStatesAreDistinct() {
        // EXPIRED means the clock ran out; TERMINATED means somebody stopped
        // it. Collapsing them makes "how many customers abandon at payment"
        // unanswerable.
        assertEquals(State.EXPIRED, CashfreeGateway.mapState("EXPIRED"));
        assertEquals(State.CANCELLED, CashfreeGateway.mapState("TERMINATED"));
        assertEquals(State.CANCELLED, CashfreeGateway.mapState("TERMINATION_REQUESTED"));
    }

    @Test
    @DisplayName("Vocabulary Cashfree adds later is UNKNOWN, never a guess")
    void unfamiliarStatusIsInert() {
        // UNKNOWN changes nothing downstream. Defaulting an unfamiliar word
        // to FAILED would let a future API version cancel live payments.
        assertEquals(State.UNKNOWN, CashfreeGateway.mapState("SOME_FUTURE_STATE"));
        assertEquals(State.UNKNOWN, CashfreeGateway.mapState(null));
    }
}
