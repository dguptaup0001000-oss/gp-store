package com.gpstore.order.cancellation;

import com.gpstore.entity.Order;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.enums.OrderFault;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.repository.StoreOperationsSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * What a cancellation costs: the merchant's number, inside the platform's cap.
 *
 * <p>§10 SPLITS THIS IN TWO and both halves are here. The merchant decides
 * whether to charge and how much - GP-STORE has no view on whether a shop in
 * one town should charge for a cancellation a shop in the next town waves
 * through. What GP-STORE does decide is the CEILING, because a "cancellation
 * charge" of 60% is not a cancellation charge, and a customer has no way to
 * find that out before they need it.
 *
 * <p>THE CAP IS CONFIGURATION, NOT A CONSTANT. §10 names 1-5% as the business
 * concept of the day, and business concepts of the day belong in a settings
 * file - so {@code platform.cancellation.max-fee-percent} is what actually
 * binds, and the 5 below is only what a deployment gets for saying nothing.
 * A shop charging 2% and a shop charging 4% are both correct.
 *
 * <p>TWO THINGS ARE FREE, ALWAYS, and neither is the shop's to override:
 * <ul>
 *   <li>§9's window. For the first few seconds after placing an order,
 *       cancelling costs nothing - that is the countdown the app has always
 *       drawn, now enforced by the server that takes the money rather than by
 *       the screen that draws the timer.</li>
 *   <li>§12's fault rule. If the shop could not fulfil the order, the
 *       customer did not cause the cancellation and does not pay for it. This
 *       is checked before the shop's own terms are even read, so there is no
 *       arrangement of settings that can charge for a merchant's failure.</li>
 * </ul>
 */
@Service
public class CancellationPolicy {

    /**
     * The ceiling when nothing is configured.
     *
     * <p>Five, because §10 named 1-5%. It is a fallback for a missing
     * property and nothing else: no code compares against it, and a
     * deployment that has an opinion sets the property.
     */
    private static final BigDecimal DEFAULT_MAX_FEE_PERCENT = new BigDecimal("5");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final StoreOperationsSettingsRepository settingsRepository;
    private final BigDecimal maxFeePercent;

    public CancellationPolicy(
            StoreOperationsSettingsRepository settingsRepository,
            @Value("${platform.cancellation.max-fee-percent:}") String configuredMax) {
        this.settingsRepository = settingsRepository;
        this.maxFeePercent = parseCap(configuredMax);
    }

    private static BigDecimal parseCap(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_FEE_PERCENT;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            // A negative or absurd cap is a typo, and a typo must not be able
            // to make every shop's fee illegal or every fee unlimited.
            if (parsed.signum() < 0 || parsed.compareTo(HUNDRED) > 0) {
                return DEFAULT_MAX_FEE_PERCENT;
            }
            return parsed;
        } catch (NumberFormatException notANumber) {
            return DEFAULT_MAX_FEE_PERCENT;
        }
    }

    /** The highest percentage any shop on this deployment may charge. */
    public BigDecimal maxFeePercent() {
        return maxFeePercent;
    }

    /**
     * The terms of ONE shop, read in that shop's own scope.
     *
     * <p>The scope is switched explicitly rather than assumed, because a
     * cancellation is one of the few places a customer's request touches a
     * shop-owned row for an order that may not be from the shop currently in
     * scope - one account, orders from many shops (§5). Narrowing to the
     * ORDER's shop is the correct scope and is derived from the order, never
     * from anything the caller sent.
     */
    public CancellationTerms termsFor(Long shopId) {
        if (shopId == null) {
            return CancellationTerms.unset();
        }
        StoreOperationsSettings settings = TenantContext.runWithin(
                TenantScope.ofShop(shopId),
                () -> settingsRepository.findByShopId(shopId).orElse(null));
        return CancellationTerms.of(settings);
    }

    /**
     * What cancelling this order at this moment would cost.
     *
     * @param order the order being cancelled - its own shop's terms apply
     * @param fault whose doing the cancellation is. MERCHANT, NOBODY and
     *              "not decided" (null) all cost the customer nothing (§12)
     * @param at    the moment being asked about, in UTC to match
     *              {@link Order#getOrderDate()}. The caller's clock, never
     *              this method's - a quote and the cancellation it leads to
     *              must be able to agree
     */
    public CancellationCharge quote(Order order, OrderFault fault, LocalDateTime at) {
        if (order == null) {
            return CancellationCharge.free(false, null, "No order.");
        }

        // §12 FIRST, before the shop's terms are even read. A shop that
        // cannot fulfil an order it accepted has caused this cancellation,
        // and billing the customer for the shop's own failure is the exact
        // thing §12 forbids. Null - a platform cancellation during a dispute
        // where nobody has decided yet - is treated the same way: the
        // customer is not charged on the strength of an open question.
        if (fault != OrderFault.CUSTOMER) {
            return CancellationCharge.free(false, null,
                    "No cancellation charge. This order was not cancelled by you.");
        }

        CancellationTerms terms = termsFor(order.getShopId());

        LocalDateTime placed = order.getOrderDate();
        LocalDateTime freeUntil = placed == null ? null : placed.plusSeconds(terms.freeSeconds());
        LocalDateTime now = at == null ? LocalDateTime.now(ZoneOffset.UTC) : at;

        if (freeUntil != null && now.isBefore(freeUntil)) {
            return CancellationCharge.free(true, freeUntil,
                    "Free to cancel for " + terms.freeSeconds() + " seconds after ordering.");
        }

        if (!terms.chargesAFee()) {
            return CancellationCharge.free(false, freeUntil,
                    "This shop does not charge for cancelling.");
        }

        BigDecimal percent = terms.feePercent().min(maxFeePercent);
        BigDecimal base = chargeableBase(order, terms);
        if (base.signum() <= 0) {
            return CancellationCharge.free(false, freeUntil,
                    "This shop does not charge for cancelling.");
        }

        BigDecimal amount = base.multiply(percent)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);

        if (amount.signum() <= 0) {
            return CancellationCharge.free(false, freeUntil,
                    "This shop does not charge for cancelling.");
        }

        return new CancellationCharge(
                amount, false, freeUntil, percent, base,
                "This shop charges " + percent.stripTrailingZeros().toPlainString()
                        + "% to cancel once the free window has passed"
                        + (terms.chargesDelivery() ? ", including delivery" : "") + ".");
    }

    /**
     * What the percentage is taken on.
     *
     * <p>Delivery is EXCLUDED by default and that is a deliberate default,
     * not an oversight: the fee exists to cover the shop's own effort, and
     * the delivery charge is money the customer paid for a journey that did
     * not happen. A shop that has already dispatched may turn it on.
     *
     * <p>{@code totalAmount} already includes the delivery fee (checkout adds
     * it), so the base is built by subtracting rather than by re-adding -
     * re-adding would double it for any shop that switched the flag on.
     */
    private BigDecimal chargeableBase(Order order, CancellationTerms terms) {
        BigDecimal total = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        if (terms.chargesDelivery()) {
            return total.max(BigDecimal.ZERO);
        }
        BigDecimal delivery = order.getDeliveryFee() == null ? BigDecimal.ZERO : order.getDeliveryFee();
        return total.subtract(delivery).max(BigDecimal.ZERO);
    }
}
