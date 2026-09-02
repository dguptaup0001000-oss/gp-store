package com.gpstore.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * How much of an order to send back.
 *
 * THE AMOUNT IS THE ONLY THING THE CLIENT MAY SAY, and it is checked against
 * the payment server-side before a rupee moves - see
 * PaymentService.amountToRefund. Nothing here is trusted: an admin screen
 * with a typo and a hand-rolled request look identical from the server, so
 * both are validated the same way.
 *
 * OMITTING IT MEANS THE WHOLE ORDER, which keeps every existing caller
 * working unchanged - cancelling an order, and the admin's plain Refund
 * button, both send no body at all.
 */
public class RefundRequest {

    /**
     * Null for a full refund. Two decimal places, because money is: the
     * annotation refuses 10.005 at the edge rather than letting a rounding
     * question reach the payment provider.
     */
    @Positive(message = "A refund has to be for more than zero")
    @Digits(integer = 10, fraction = 2,
            message = "A refund amount can have at most two decimal places")
    private BigDecimal amount;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
