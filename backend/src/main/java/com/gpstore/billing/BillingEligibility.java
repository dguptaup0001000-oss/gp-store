package com.gpstore.billing;

import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a merchant qualifies for the no-order fee refund (§7).
 *
 * <p>A LIST OF NAMED CHECKS, NOT A BOOLEAN EXPRESSION, because §7 says the
 * final conditions are not decided and names four candidates: active, in good
 * standing, not fraudulent, not intentionally unavailable for the whole
 * period. Two of those are answerable from data that exists today and two are
 * not, so what is built is the shape: each rule is a named method returning a
 * reason when it fails, and adding the third and fourth is adding a method
 * rather than editing an expression somebody would have to re-derive.
 *
 * <p>THE FAILURE REASONS ARE THE POINT. A merchant told "you did not qualify"
 * with no reason is a merchant who will ask, and the answer has to be in the
 * system rather than in whoever wrote the job. §3 asks for transparent rules
 * with reason codes, and this is that for billing.
 *
 * <p>NOT IMPLEMENTED HERE, and deliberately not stubbed: "not fraudulent" and
 * "not intentionally unavailable" both need a governance record that does not
 * exist yet (Part 4 §2). A check that always returns true would look like a
 * rule and be a decoration.
 */
public final class BillingEligibility {

    private BillingEligibility() {
    }

    /**
     * @param eligible whether the fee comes back
     * @param failures the rules that said no, by reason code, in order
     */
    public record Verdict(boolean eligible, List<String> failures) {

        public String summary() {
            return eligible ? "ELIGIBLE" : String.join(", ", failures);
        }
    }

    /**
     * The rules answerable from data that exists today.
     *
     * @param merchant       the merchant whose week it was
     * @param completedOrders how many orders they actually completed
     */
    public static Verdict forNoOrderRefund(Merchant merchant, long completedOrders) {
        List<String> failures = new ArrayList<>();

        if (completedOrders > 0) {
            // Not a failure of standing - simply not the case §7 is about.
            failures.add("HAD_ORDERS");
        }
        if (merchant == null) {
            failures.add("NO_SUCH_MERCHANT");
            return new Verdict(false, failures);
        }
        if (!Boolean.TRUE.equals(merchant.getActive())) {
            failures.add("MERCHANT_INACTIVE");
        }
        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            // Good standing, as far as the data goes today: a suspended or
            // removed merchant is not owed a week's fee back.
            failures.add("NOT_IN_GOOD_STANDING");
        }

        return new Verdict(failures.isEmpty(), List.copyOf(failures));
    }
}
