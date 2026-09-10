package com.gpstore.enums;

/**
 * Whose doing the order's ending was - which is a different question from who
 * pressed the button.
 *
 * <p>THE SEPARATION IS THE WHOLE OF §12. A customer who cancels because the
 * shop rang to say the atta never arrived CANCELLED the order and is not at
 * FAULT for it, and billing them a cancellation charge for the shop's
 * stock-out is precisely the unfairness §12 exists to prevent. One column
 * cannot carry both facts, so there are two.
 *
 * <p>NOBODY IS A REAL ANSWER, not a placeholder. A flood shut the road; a
 * power cut stopped the packing. Nobody should be charged and nobody should
 * be marked down for it, and that is different from null - which means the
 * question has not been answered yet.
 */
public enum OrderFault {

    /** Changed their mind, ordered by mistake, was not at home. */
    CUSTOMER,

    /** Could not fulfil, cancelled after accepting, sent the wrong thing. */
    MERCHANT,

    /** Genuinely neither: weather, a road closure, a power cut. */
    NOBODY;

    /**
     * Whether a cancellation charge may be applied at all (§12).
     *
     * <p>Only the customer's own doing earns one. This is asked in one place
     * so that the answer cannot drift between the screen that shows the charge
     * and the code that takes it.
     */
    public boolean maybeChargeable() {
        return this == CUSTOMER;
    }
}
