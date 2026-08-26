package com.gpstore.payment;

import com.gpstore.payment.gateway.PaymentGateway.GatewayOrderStatus.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure decisions inside the gateway flow, tested without a database or a
 * network: how provider vocabulary maps to ours, how amounts are compared,
 * and how a late webhook finds its order.
 */
class GatewayPaymentLogicTest {

    // ---- amount comparison -------------------------------------------

    @Test
    @DisplayName("100 and 100.00 are the same amount")
    void scaleDoesNotAffectEquality() {
        // BigDecimal.equals is scale-sensitive, so using it here would reject
        // every correct payment Cashfree ever settles: it returns two decimal
        // places and our order totals may carry a different scale.
        assertTrue(GatewayPaymentService.amountMatches(
                new BigDecimal("100"), new BigDecimal("100.00")));
        assertTrue(GatewayPaymentService.amountMatches(
                new BigDecimal("249.50"), new BigDecimal("249.5")));
    }

    @Test
    @DisplayName("A short payment is not a match")
    void underpaymentRejected() {
        // The attack: settle 1.00 against a 500.00 order and collect the
        // groceries.
        assertFalse(GatewayPaymentService.amountMatches(
                new BigDecimal("500.00"), new BigDecimal("1.00")));
    }

    @Test
    @DisplayName("An overpayment is not a match either")
    void overpaymentRejected() {
        // Also refused rather than banked - it means the two sides disagree
        // about this order, and acting on a disagreement is how a refund
        // dispute starts.
        assertFalse(GatewayPaymentService.amountMatches(
                new BigDecimal("500.00"), new BigDecimal("500.01")));
    }

    @Test
    @DisplayName("A missing amount never matches")
    void nullAmountsRejected() {
        assertFalse(GatewayPaymentService.amountMatches(null, new BigDecimal("10")));
        assertFalse(GatewayPaymentService.amountMatches(new BigDecimal("10"), null));
        assertFalse(GatewayPaymentService.amountMatches(null, null));
    }

    // ---- provider vocabulary -----------------------------------------

    @Test
    @DisplayName("Only SUCCESS means paid")
    void paymentStatusMapping() {
        assertEquals(State.PAID, GatewayPaymentService.mapPaymentStatus("SUCCESS"));
        assertEquals(State.FAILED, GatewayPaymentService.mapPaymentStatus("FAILED"));
        assertEquals(State.FAILED, GatewayPaymentService.mapPaymentStatus("USER_DROPPED"));
        assertEquals(State.CANCELLED, GatewayPaymentService.mapPaymentStatus("CANCELLED"));
        assertEquals(State.CANCELLED, GatewayPaymentService.mapPaymentStatus("TERMINATED"));
        assertEquals(State.EXPIRED, GatewayPaymentService.mapPaymentStatus("EXPIRED"));
    }

    @Test
    @DisplayName("A pending payment is ACTIVE, never FAILED")
    void pendingIsNotFailure() {
        // Mapping "not finished yet" to failure is how a customer still
        // typing an OTP gets their payment marked failed underneath them.
        assertEquals(State.ACTIVE, GatewayPaymentService.mapPaymentStatus("PENDING"));
        assertEquals(State.ACTIVE, GatewayPaymentService.mapPaymentStatus("NOT_ATTEMPTED"));
    }

    @Test
    @DisplayName("An unrecognised status is UNKNOWN, and UNKNOWN changes nothing")
    void unknownStatusIsInert() {
        // Cashfree may add vocabulary. Defaulting an unfamiliar word to
        // FAILED would let a future API version cancel live payments.
        assertEquals(State.UNKNOWN, GatewayPaymentService.mapPaymentStatus("SOME_NEW_STATUS_2027"));
        assertEquals(State.UNKNOWN, GatewayPaymentService.mapPaymentStatus(null));
    }

    // ---- late webhooks for superseded attempts ------------------------

    @Test
    @DisplayName("A webhook for a superseded retry still finds its order")
    void internalOrderIdRecoverableFromProviderOrderId() {
        // A customer who retries gets a NEW gateway order id. The prefix
        // still names which order row to lock. SUCCESS for the old attempt
        // is not applied to the current row (GatewayPaymentStateTest).
        assertEquals(1234L, GatewayPaymentService.internalOrderIdFrom("GP-1234-7f3a9b"));
        assertEquals(7L, GatewayPaymentService.internalOrderIdFrom("GP-7-aaaa"));
    }

    @Test
    @DisplayName("An id from somewhere else resolves to nothing, rather than to order 0")
    void foreignOrderIdsAreNotGuessed() {
        // A misconfigured dashboard pointing at this endpoint must produce
        // "unknown order", never a coincidental match.
        assertNull(GatewayPaymentService.internalOrderIdFrom("someone-elses-order-1"));
        assertNull(GatewayPaymentService.internalOrderIdFrom("GP-notanumber-xx"));
        assertNull(GatewayPaymentService.internalOrderIdFrom(null));
        assertNull(GatewayPaymentService.internalOrderIdFrom(""));
    }
}
