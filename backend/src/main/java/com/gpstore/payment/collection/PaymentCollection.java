package com.gpstore.payment.collection;

import com.gpstore.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Who collects the money for one shop's online orders.
 *
 * <p>THE RULE THIS SEAM SERVES IS ALREADY DECIDED, in Decision W1
 * ({@code docs/architecture/03-decision-w1-money-model.md}): a merchant's
 * product proceeds belong to that merchant, and GP-STORE is not to run a
 * pooled account holding everybody's money and paying it out later. See
 * {@link PaymentCollectionModel}. What is open is HOW to honour that
 * compliantly - the provider, merchant onboarding and KYC, the settlement
 * mechanism, how fees are borne, how refunds are funded and reversed, and the
 * regulatory position that follows. Those are provider, legal and commercial
 * decisions, and §17 says this codebase builds the boundary rather than
 * inventing what sits behind it.
 *
 * <p>PROVIDER-AGNOSTIC ON PURPOSE, AND CHECKED. Nothing in this class names a
 * payment provider, or an account identifier belonging to one -
 * PaymentCollectionBoundaryTest scans this file for provider names and fails
 * the build if one appears, which is why even the examples are absent from
 * this comment. It depends on the {@link PaymentGateway} interface only to
 * answer "is a gateway configured at all" - a COD-only deployment has none -
 * and takes the provider's own name from {@code provider()} rather than
 * assuming it. When the decisions above are made, the provider-specific work
 * happens behind this seam and this file should barely change.
 *
 * <p>WHAT IT IS NOT: a stub for a feature. There is no half-written
 * merchant-collects path behind a flag. Asking for one is a startup failure,
 * because a deployment that believes merchants are collecting while every
 * rupee lands in the platform's account would report the wrong thing on every
 * merchant's earnings screen and in every settlement, and would do it
 * silently.
 *
 * <p>PER SHOP FROM THE START, even though today every shop gets the same
 * answer. The signature is the thing being designed: when a merchant does
 * collect directly, the change is one implementation of this method, not a
 * search for every place that assumed one account.
 */
@Component
public class PaymentCollection {

    private static final Logger log = LoggerFactory.getLogger(PaymentCollection.class);

    private final PaymentCollectionModel model;

    /**
     * Whether this deployment has any online gateway at all.
     *
     * <p>Resolved once, from whichever {@link PaymentGateway} is present, so
     * that no provider's configuration class is named here. Absent means a
     * COD-only deployment, which is a real shape and not a misconfiguration.
     */
    private final boolean gatewayConfigured;
    private final String providerName;

    public PaymentCollection(
            @Value("${payments.collection-model:PLATFORM_COLLECTS}") String configured,
            ObjectProvider<PaymentGateway> gateways) {
        this.model = parse(configured);

        PaymentGateway gateway = gateways.getIfAvailable();
        this.gatewayConfigured = gateway != null;
        this.providerName = gateway == null ? null : gateway.provider().name();

        if (this.model == PaymentCollectionModel.MERCHANT_COLLECTS) {
            // FAILS TO START, and that is the point of the flag existing at
            // all. See PaymentCollectionModel.MERCHANT_COLLECTS.
            throw new IllegalStateException(
                    "payments.collection-model=MERCHANT_COLLECTS is configured, but no "
                            + "per-merchant collection is implemented: every online payment "
                            + "would still land in GP-STORE's own gateway account while every "
                            + "earnings screen and settlement said otherwise. The requirement "
                            + "is right; the provider, onboarding/KYC, settlement, fee and "
                            + "refund treatment behind it are not decided yet.");
        }

        if (!this.model.meetsTheMerchantOwnsTheirMoneyRule()) {
            // A WARNING, NOT AN INFO LINE, AND NOT A CRASH. Not info, because
            // this is a known gap against a decided rule and it should be
            // visible in the log of every environment that starts this way.
            // Not a crash, because refusing to boot would take the shop down
            // over something no deploy can fix today.
            log.warn("Online payments are collected as {}: GP-STORE's own account receives "
                            + "every shop's product money and owes it onward. Decision W1 says a "
                            + "merchant's proceeds are the merchant's, so this is a gap against a "
                            + "recorded decision - an interim state, not the model. Reaching {} "
                            + "needs a provider, merchant onboarding and KYC, a mechanism for "
                            + "funds to reach the merchant, and the fee and refund treatment to "
                            + "be decided.",
                    this.model, PaymentCollectionModel.MERCHANT_COLLECTS);
        } else {
            log.info("Online payments are collected by: {}", this.model);
        }
    }

    /**
     * Who receives the money for an order placed at this shop.
     *
     * @param shopId the shop the order belongs to. Unused today - every shop
     *               gets the same answer - and present because it is the whole
     *               reason for the seam. A method without it would have to be
     *               found and changed everywhere the day the answer differs
     *               per shop, which is exactly the migration this avoids.
     */
    public Collector forShop(Long shopId) {
        return new Collector(
                model,
                // AN OPAQUE REFERENCE, NOT A PROVIDER'S ACCOUNT ID. It says
                // whose account this is, not which account - naming a real one
                // here would be inventing the settlement identity that has not
                // been decided.
                gatewayConfigured ? "platform" : null,
                providerName,
                "GP-STORE collects this payment and owes it to the shop.");
    }

    /** The model this deployment runs. */
    public PaymentCollectionModel model() {
        return model;
    }

    /**
     * Whether this deployment currently satisfies the rule that a merchant's
     * product money is the merchant's.
     *
     * <p>False today, everywhere. Exposed so a report, an ops screen or a test
     * can ask rather than infer it from an enum comparison written out in
     * several places.
     */
    public boolean meetsTheMerchantOwnsTheirMoneyRule() {
        return model.meetsTheMerchantOwnsTheirMoneyRule();
    }

    /**
     * @param model      how this shop's money is collected
     * @param accountRef an opaque reference to WHOSE account receives it -
     *                   never a provider's account identifier - or null when
     *                   no gateway is configured (a COD-only deployment)
     * @param provider   the gateway's own name for itself, or null when there
     *                   is none. Read from the gateway rather than assumed, so
     *                   nothing here hard-codes a provider
     * @param note       what a merchant is told, in words rather than an enum
     */
    public record Collector(PaymentCollectionModel model, String accountRef,
                            String provider, String note) {

        /** Whether the shop's own account receives this directly. */
        public boolean merchantCollectsDirectly() {
            return model == PaymentCollectionModel.MERCHANT_COLLECTS;
        }
    }

    private static PaymentCollectionModel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaymentCollectionModel.PLATFORM_COLLECTS;
        }
        try {
            return PaymentCollectionModel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // NOT a silent fall-back, unlike PlatformProperties: an unreadable
            // MODE is a deployment that meant something about money and did
            // not get it. Failing closed here would mean guessing which.
            throw new IllegalStateException(
                    "Unknown payments.collection-model '" + raw + "'. Valid values: "
                            + java.util.Arrays.toString(PaymentCollectionModel.values()));
        }
    }
}
