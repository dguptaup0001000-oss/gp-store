package com.gpstore.billing;

import com.gpstore.platform.Merchant;
import com.gpstore.platform.MerchantRepository;
import com.gpstore.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * CLOSING A MERCHANT'S WEEK: §6's hybrid model, written as ledger rows.
 *
 * <p>THE MODEL. A weekly platform fee P, commission C on successful product
 * sales, and the fee acting as a credit against the commission - so the
 * merchant pays max(P, C). Below P they pay P; above it they pay C.
 *
 * <p>WRITTEN AS THREE ROWS RATHER THAN ONE TOTAL, and that is the design
 * decision worth defending. The obvious implementation computes max(P, C) and
 * writes it; this writes +P, +C and -min(P, C), whose sum is the same number.
 * The difference is what a merchant sees on a statement: not "you owe 812"
 * but a fee, a commission, and a credit that explains why the two did not
 * simply add up. A number you cannot check is a number that gets disputed.
 *
 * <p>NO AMOUNTS IN THIS FILE. P and the commission rate come from a
 * BillingPlan row that Platform Admin configured, in force on the period's
 * dates. A merchant with no tier, or a tier with no plan, is NOT BILLED - and
 * that is every merchant today (§5: the commercial amounts are not decided).
 *
 * <p>IDEMPOTENT BY CONSTRUCTION. Closing a week twice - a retry, two job
 * instances, a person - must not bill it twice, and the guard is the database:
 * one period row per merchant per week, and one COMMISSION row per order.
 */
@Service
public class WeeklyBilling {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBilling.class);

    private final BillingPlanRepository plans;
    private final BillingPeriodRepository periods;
    private final MerchantLedgerRepository ledger;
    private final MerchantRepository merchants;
    private final MerchantSales sales;
    private final AuditLogService auditLog;

    public WeeklyBilling(BillingPlanRepository plans,
                         BillingPeriodRepository periods,
                         MerchantLedgerRepository ledger,
                         MerchantRepository merchants,
                         MerchantSales sales,
                         AuditLogService auditLog) {
        this.plans = plans;
        this.periods = periods;
        this.ledger = ledger;
        this.merchants = merchants;
        this.sales = sales;
        this.auditLog = auditLog;
    }

    /**
     * What closing a week did, for the caller and for the report.
     *
     * @param billed        false when the merchant has no tier or no plan -
     *                      which is not an error, it is a merchant nobody has
     *                      priced yet
     * @param platformFee   P, or zero when not billed
     * @param commission    C, or zero
     * @param commissionCredit the credit applied, min(P, C)
     * @param feeRefunded   P returned under §7, or zero
     * @param payable       what the merchant owes for the week: the sum of the
     *                      rows written, which is max(P, C) less any refund
     */
    public record WeekClosed(boolean billed, Long periodId, BigDecimal platformFee,
                             BigDecimal commission, BigDecimal commissionCredit,
                             BigDecimal feeRefunded, BigDecimal payable, String note) {}

    /**
     * Closes the week a date falls in, for one merchant.
     *
     * @param actor who or what is doing this - a job name or an administrator
     */
    @Transactional
    public WeekClosed closeWeek(Long merchantId, LocalDate anyDayInTheWeek, String actor) {
        Merchant merchant = merchants.findById(merchantId).orElse(null);
        if (merchant == null) {
            return notBilled(null, "NO_SUCH_MERCHANT");
        }

        BillingPeriod period = openOrExistingPeriod(merchantId, anyDayInTheWeek);
        if (period.getStatus() != BillingPeriod.Status.OPEN) {
            // ALREADY CLOSED. Not an error and not a second bill: the caller
            // gets what the week already came to, which is what a retry should
            // see.
            return new WeekClosed(true, period.getId(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    ledger.totalForPeriod(period.getId()), "ALREADY_CLOSED");
        }

        // A merchant with no tier is not on a price list, and a tier with no
        // plan in force has no price. Either way nobody is billed, and the
        // week closes at zero rather than at a number this code invented.
        Optional<BillingPlan> plan = planFor(merchant, period.getStartsOn());
        if (plan.isEmpty()) {
            close(period);
            return notBilled(period.getId(),
                    merchant.getTier() == null ? "MERCHANT_HAS_NO_TIER" : "NO_PLAN_IN_FORCE");
        }

        BillingPlan pricing = plan.get();
        MerchantSales.Week week = sales.forMerchant(merchantId, period.getStartsOn(), period.getEndsOn());

        BigDecimal fee = scaled(pricing.getWeeklyFee());

        // +P
        ledger.save(MerchantLedgerEntry.of(merchantId, period.getId(),
                        LedgerEntryType.PLATFORM_FEE, fee, "WEEKLY_PLATFORM_FEE", actor)
                .describedAs("Weekly platform fee, " + merchant.getTier() + " tier, week of "
                        + period.getStartsOn()));

        // +C, ONE ROW PER SALE. §8 requires commission to be reversible when a
        // completed order is later refunded, and a charge can only be reversed
        // against the sale it came from if it knew which sale that was. It is
        // also what a merchant disputing a line needs: not "commission, 240"
        // but the order that produced it.
        //
        // The week's commission is therefore the SUM of the rows rather than a
        // rate applied to a total - which also keeps the statement's rows
        // adding up to its total exactly, instead of a paisa away from it.
        BigDecimal commission = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (MerchantSales.Line line : week.lines()) {
            BigDecimal lineCommission = pricing.commissionOn(line.commissionable());
            if (lineCommission.signum() <= 0) {
                continue;
            }
            commission = commission.add(lineCommission);
            ledger.save(MerchantLedgerEntry.of(merchantId, period.getId(),
                            LedgerEntryType.COMMISSION, lineCommission, "SALES_COMMISSION", actor)
                    .forOrder(line.orderId())
                    .describedAs("Commission on " + line.commissionable() + " at "
                            + pricing.getCommissionBps() + " bps"));
        }

        BigDecimal credit = fee.min(commission);

        if (credit.signum() > 0) {
            // -min(P, C). THE ROW THAT MAKES max(P, C) VISIBLE rather than
            // asserted: the statement shows the fee, the commission, and why
            // the merchant is not paying both.
            ledger.save(MerchantLedgerEntry.of(merchantId, period.getId(),
                            LedgerEntryType.COMMISSION_CREDIT, credit.negate(),
                            "WEEKLY_FEE_AS_COMMISSION_CREDIT", actor)
                    .describedAs("The weekly fee is credited against commission, so the week "
                            + "costs the greater of the two rather than their sum"));
        }

        // §7: a week with no completed orders gets the fee back, IF the
        // merchant qualifies - and the reasons are recorded either way,
        // because a merchant told "you did not qualify" will ask why.
        BigDecimal refunded = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BillingEligibility.Verdict verdict =
                BillingEligibility.forNoOrderRefund(merchant, week.completedOrders());
        if (verdict.eligible() && fee.signum() > 0) {
            refunded = fee;
            ledger.save(MerchantLedgerEntry.of(merchantId, period.getId(),
                            LedgerEntryType.FEE_REFUND_NO_ORDERS, fee.negate(),
                            "NO_COMPLETED_ORDERS_IN_PERIOD", actor)
                    .describedAs("No completed orders in the week, so the platform fee is "
                            + "returned in full"));
        }

        close(period);
        BigDecimal payable = ledger.totalForPeriod(period.getId());

        auditLog.log("BILLING_WEEK_CLOSED", "BillingPeriod", period.getId(),
                "merchant=" + merchantId + " fee=" + fee + " commission=" + commission
                        + " credit=" + credit + " refund=" + refunded + " payable=" + payable
                        + " eligibility=" + verdict.summary());

        return new WeekClosed(true, period.getId(), fee, commission, credit, refunded, payable,
                verdict.summary());
    }

    /**
     * Takes back the commission on an order that was later refunded (§8).
     *
     * <p>A NEW ROW POINTING AT THE OLD ONE, never an edit - the ledger trigger
     * would refuse an edit anyway. The reversal is proportional: a partial
     * refund takes back a proportional part of the commission, because the
     * merchant did keep the rest of the sale.
     */
    @Transactional
    public Optional<MerchantLedgerEntry> reverseCommission(Long orderId, BigDecimal refundedAmount,
                                                           String actor) {
        Optional<MerchantLedgerEntry> charged =
                ledger.findByOrderIdAndEntryType(orderId, LedgerEntryType.COMMISSION);
        if (charged.isEmpty() || refundedAmount == null || refundedAmount.signum() <= 0) {
            return Optional.empty();
        }
        MerchantLedgerEntry original = charged.get();

        BigDecimal saleBase = sales.commissionableForOrder(orderId);
        if (saleBase == null || saleBase.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal proportion = refundedAmount.min(saleBase)
                .divide(saleBase, 6, RoundingMode.HALF_UP);
        BigDecimal giveBack = original.getAmount().multiply(proportion)
                .setScale(2, RoundingMode.HALF_UP);
        if (giveBack.signum() <= 0) {
            return Optional.empty();
        }

        MerchantLedgerEntry reversal = ledger.save(MerchantLedgerEntry.of(
                        original.getMerchantId(), original.getBillingPeriodId(),
                        LedgerEntryType.COMMISSION_REVERSAL, giveBack.negate(),
                        "ORDER_REFUNDED", actor)
                .forOrder(orderId)
                .reversing(original.getId())
                .describedAs("Commission returned on " + refundedAmount + " refunded of "
                        + saleBase));

        auditLog.log("BILLING_COMMISSION_REVERSED", "MerchantLedgerEntry", reversal.getId(),
                "order=" + orderId + " returned=" + giveBack);
        return Optional.of(reversal);
    }

    /** What a merchant owes right now: the sum of their rows, never a stored total. */
    @Transactional(readOnly = true)
    public BigDecimal balanceOf(Long merchantId) {
        return scaled(ledger.balanceOf(merchantId));
    }

    /** A merchant's ledger, newest first. */
    @Transactional(readOnly = true)
    public List<MerchantLedgerEntry> statementFor(Long merchantId) {
        return ledger.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    // ------------------------------------------------------------- plumbing

    private Optional<BillingPlan> planFor(Merchant merchant, LocalDate on) {
        if (merchant.getTier() == null) {
            return Optional.empty();
        }
        return plans.inForce(merchant.getTier(), on).stream().findFirst();
    }

    private BillingPeriod openOrExistingPeriod(Long merchantId, LocalDate day) {
        BillingPeriod candidate = BillingPeriod.weekOf(merchantId, day);
        return periods.findByMerchantIdAndStartsOn(merchantId, candidate.getStartsOn())
                .orElseGet(() -> periods.save(candidate));
    }

    private void close(BillingPeriod period) {
        period.setStatus(BillingPeriod.Status.CLOSED);
        period.setClosedAt(LocalDateTime.now());
        periods.save(period);
    }

    private WeekClosed notBilled(Long periodId, String why) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        log.debug("Not billing: {}", why);
        return new WeekClosed(false, periodId, zero, zero, zero, zero, zero, why);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
