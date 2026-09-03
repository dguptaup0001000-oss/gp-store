package com.gpstore.returns;

import jakarta.validation.constraints.Size;

/**
 * The shop's answer to a return.
 *
 * NO AMOUNT HERE EITHER. An approval refunds what the returned lines were
 * charged, computed from the order. Letting the decision carry a figure would
 * put the shop's refund total in a request body, where a typo or a tampered
 * client decides it.
 */
public class ReturnDecisionBody {

    /** Required when rejecting - the customer is shown this. */
    @Size(max = 500)
    private String note;

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
