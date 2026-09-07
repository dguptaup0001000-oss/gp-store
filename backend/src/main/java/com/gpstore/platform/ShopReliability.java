package com.gpstore.platform;

import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.OrderReturnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * WHAT A SHOP HAS ACTUALLY DONE, computed from its own trading record.
 *
 * <p>THE POINT OF §10, and the reason this is a service rather than a column.
 * VERIFIED and BUSINESS_VERIFIED are things GP-STORE grants after looking at
 * documents; TRUSTED is not granted at all. It is what the shop's record adds
 * up to, recomputed from orders and returns every time anybody asks - so
 * there is nothing to set, nothing to backfill, and nothing to sell. A shop
 * that stops delivering stops being trusted the same week, without anybody
 * remembering to take a badge away.
 *
 * <p>NOTHING HERE TAKES A SHOP ID. Both repositories are JPQL over shop-owned
 * entities, so the figures are the shop in scope's. A reliability score
 * computed for a shop the caller named would be one shop's record read by
 * whoever asked for it, which is the shape §13 exists to prevent.
 *
 * <p>A NEW SHOP IS NOT AN UNTRUSTWORTHY SHOP. Below the minimum order count
 * the answer is "not yet", never "no": a kirana that opened last week has not
 * failed anything, and treating a thin record as a bad one would make the
 * badge impossible to earn rather than merely hard.
 */
@Service
public class ShopReliability {

    private static final Logger log = LoggerFactory.getLogger(ShopReliability.class);

    /**
     * How far back the record is read.
     *
     * <p>Ninety days rather than all time, deliberately: a shop that was bad a
     * year ago and has traded well since is a shop that has improved, and a
     * badge that never forgets is one nobody can recover from. It also keeps
     * the query bounded as the order table grows.
     */
    private static final int WINDOW_DAYS = 90;

    /** Below this the record is too thin to mean anything either way. */
    private static final long MIN_ORDERS_FOR_TRUST = 25;

    /** Of the orders it took, this many have to have arrived. */
    private static final double MIN_COMPLETION_RATE = 0.92;

    /** And this few can have come back. */
    private static final double MAX_RETURN_RATE = 0.08;

    private final OrderRepository orders;
    private final OrderReturnRepository returns;

    public ShopReliability(OrderRepository orders, OrderReturnRepository returns) {
        this.orders = orders;
        this.returns = returns;
    }

    /**
     * A shop's record, and whether it adds up to trust.
     *
     * @param orderCount     orders taken in the window
     * @param completed      of those, orders that reached the customer
     * @param cancelled      of those, orders the shop did not complete
     * @param returned       orders with a return raised against them
     * @param completionRate completed / orderCount, or 0 with no orders
     * @param returnRate     returned / orderCount, or 0 with no orders
     * @param trusted        whether §10's earned badge applies right now
     * @param whyNot         what is missing, for the merchant's own screen -
     *                       null when trusted. A badge a shopkeeper cannot
     *                       find out how to earn is a badge that looks bought.
     */
    public record Record(long orderCount, long completed, long cancelled, long returned,
                         double completionRate, double returnRate,
                         boolean trusted, String whyNot) {}

    /**
     * The shop in scope's record over the window.
     *
     * <p>FAILS TO "NOT TRUSTED", not to trusted: if the record cannot be read,
     * the honest answer is that nothing has been shown, and showing a badge on
     * the strength of a failed query is the one direction that misleads a
     * customer.
     */
    @Transactional(readOnly = true)
    public Record forCurrentShop() {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(WINDOW_DAYS);

            long total = 0;
            long completed = 0;
            long cancelled = 0;
            for (Object[] row : orders.countByStatusSince(since)) {
                String status = String.valueOf(row[0]);
                long count = ((Number) row[1]).longValue();
                total += count;
                if ("DELIVERED".equals(status)) {
                    completed += count;
                } else if ("CANCELLED".equals(status)) {
                    cancelled += count;
                }
            }

            long returned = returns.countSince(since);

            double completionRate = total == 0 ? 0 : (double) completed / total;
            double returnRate = total == 0 ? 0 : (double) returned / total;

            String whyNot = whyNotTrusted(total, completionRate, returnRate);
            return new Record(total, completed, cancelled, returned,
                    completionRate, returnRate, whyNot == null, whyNot);
        } catch (Exception ex) {
            log.warn("Could not read this shop's trading record; reporting it as unproven: {}",
                    ex.toString());
            return new Record(0, 0, 0, 0, 0, 0, false,
                    "This shop's trading record could not be read just now.");
        }
    }

    /** Whether §10's earned badge applies to the shop in scope. */
    @Transactional(readOnly = true)
    public boolean isTrusted() {
        return forCurrentShop().trusted();
    }

    /**
     * The one place the thresholds are applied, so the badge and the
     * explanation cannot disagree about why it is not showing.
     */
    private static String whyNotTrusted(long total, double completionRate, double returnRate) {
        if (total < MIN_ORDERS_FOR_TRUST) {
            return "Trusted status is earned over " + MIN_ORDERS_FOR_TRUST
                    + " orders. This shop has completed " + total + " in the last "
                    + WINDOW_DAYS + " days.";
        }
        if (completionRate < MIN_COMPLETION_RATE) {
            return "Trusted shops deliver at least "
                    + Math.round(MIN_COMPLETION_RATE * 100) + "% of the orders they accept.";
        }
        if (returnRate > MAX_RETURN_RATE) {
            return "Trusted shops keep returns under "
                    + Math.round(MAX_RETURN_RATE * 100) + "% of orders.";
        }
        return null;
    }
}
