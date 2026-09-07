package com.gpstore.payment.collection;

import com.gpstore.payment.gateway.CashfreeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Who collects the money for one shop's online orders.
 *
 * <p>WHY THIS EXISTS AS A SEAM rather than as an answer. Payment collection is
 * the one deployment-wide setting §17 explicitly says NOT to convert into
 * per-merchant accounts on the strength of an architectural preference: doing
 * so means a second Cashfree integration, a merchant-onboarding flow with KYC
 * attached, and a regulatory position somebody has to choose. So the shape of
 * the question is built now - per shop, asked in code, answerable differently
 * later - and the answer stays the one that is actually true.
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
    private final CashfreeProperties gateway;

    public PaymentCollection(
            @Value("${payments.collection-model:PLATFORM_COLLECTS}") String configured,
            CashfreeProperties gateway) {
        this.gateway = gateway;
        this.model = parse(configured);

        if (this.model == PaymentCollectionModel.MERCHANT_COLLECTS) {
            // FAILS TO START, and that is the point of the flag existing at
            // all. See PaymentCollectionModel.MERCHANT_COLLECTS.
            throw new IllegalStateException(
                    "payments.collection-model=MERCHANT_COLLECTS is configured, but no "
                            + "per-merchant collection is implemented: every online payment "
                            + "would still land in GP-STORE's own gateway account while every "
                            + "earnings screen and settlement said otherwise. Decide the model "
                            + "first (see PaymentCollectionModel), then implement it.");
        }
        log.info("Online payments are collected by: {}", this.model);
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
                PaymentCollectionModel.PLATFORM_COLLECTS,
                gateway.getAppId() == null || gateway.getAppId().isBlank()
                        ? null : "gpstore-platform",
                "GP-STORE collects this payment and owes it to the shop.");
    }

    /** The model this deployment runs. */
    public PaymentCollectionModel model() {
        return model;
    }

    /**
     * @param model      how this shop's money is collected
     * @param accountRef an opaque reference to the receiving account, or null
     *                   when no gateway is configured (a COD-only deployment)
     * @param note       what a merchant is told, in words rather than an enum
     */
    public record Collector(PaymentCollectionModel model, String accountRef, String note) {

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
