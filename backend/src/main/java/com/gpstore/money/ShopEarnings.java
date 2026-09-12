package com.gpstore.money;

import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import com.gpstore.platform.TenantContext;
import com.gpstore.repository.OrderRepository;
import com.gpstore.repository.PaymentRepository;
import com.gpstore.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What one shop took, and in what form it arrived.
 *
 * WHY THIS IS AN EARNINGS STATEMENT AND NOT A SETTLEMENT. Under decision W1
 * each merchant collects directly - GP-STORE never holds the customer's money,
 * so there is nothing for the platform to settle and no payout to schedule.
 * The number a shopkeeper needs is not "when will I be paid", it is "what came
 * in, how much of it is cash a rider is carrying, and how much went back out
 * as refunds". §103: local businesses remain independent; GP-STORE provides
 * the technology.
 *
 * THERE IS DELIBERATELY NO COMMISSION LINE. Adding a zero-valued platform fee
 * would look like a decision that has been made. It has not - decision W2
 * (invoice, subscription, or none) is open, and until it is answered a fee
 * field would be a guess printed next to real money.
 *
 * WHOSE EARNINGS. The shop on the tenant scope, which came from the
 * credential. Every query below is either rooted on a shop-owned entity, and
 * so filtered, or names the shop explicitly because it reaches one through a
 * join - see RefundRepository.settledForOrdersBetween for why that distinction
 * is not academic.
 */
@Service
public class ShopEarnings {

    /**
     * The same bound the dashboard uses. A statement is a window, and an
     * unbounded window is an unbounded scan on a database that also has to
     * serve checkout.
     */
    private static final int MAX_PERIOD_DAYS = 730;

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final PaymentRepository payments;

    public ShopEarnings(OrderRepository orders, RefundRepository refunds,
                        PaymentRepository payments) {
        this.orders = orders;
        this.refunds = refunds;
        this.payments = payments;
    }

    /**
     * @param grossSales      what customers were charged, cancelled orders excluded
     * @param refunds         what went back, of sales inside this window
     * @param netSales        what the shop actually kept
     * @param collectedOnline money that reached a gateway
     * @param collectedCash   notes a rider took at the door
     * @param collectedCodUpi paid to the rider by UPI on the doorstep
     * @param awaitingCollection COD that has been ordered but not yet handed over
     * @param orderCount      orders placed, cancelled ones included
     * @param cancelledCount  how many of those were called off
     */
    public record Statement(int periodDays, LocalDateTime from, LocalDateTime to,
                            BigDecimal grossSales, BigDecimal refunds, BigDecimal netSales,
                            BigDecimal collectedOnline, BigDecimal collectedCash,
                            BigDecimal collectedCodUpi, BigDecimal awaitingCollection,
                            long orderCount, long cancelledCount) {}

    @Transactional(readOnly = true)
    public Statement forCurrentShop(int days) {
        int window = Math.max(1, Math.min(days, MAX_PERIOD_DAYS));
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(window);
        Long shopId = TenantContext.reportingShopId();

        BigDecimal gross = orZero(orders.sumRevenueBetween(from, to));
        BigDecimal refunded = orZero(refunds.settledForOrdersBetween(from, to, shopId));

        BigDecimal online = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal codUpi = BigDecimal.ZERO;
        BigDecimal awaiting = BigDecimal.ZERO;

        for (Object[] row : payments.collectionsBetween(from, to)) {
            PaymentMethod method = (PaymentMethod) row[0];
            PaymentStatus status = (PaymentStatus) row[1];
            BigDecimal amount = orZero((BigDecimal) row[2]);
            BigDecimal rowCash = orZero((BigDecimal) row[3]);
            BigDecimal rowUpi = orZero((BigDecimal) row[4]);

            if (method == PaymentMethod.COD) {
                if (status == PaymentStatus.COD_RECEIVED) {
                    // THE SPLIT, NOT THE TOTAL, when the rider recorded one.
                    // A doorstep bill can be part notes and part UPI; falling
                    // back to the amount keeps older rows - collected before
                    // the split existed - counted as the cash they were.
                    if (rowCash.signum() > 0 || rowUpi.signum() > 0) {
                        cash = cash.add(rowCash);
                        codUpi = codUpi.add(rowUpi);
                    } else {
                        cash = cash.add(amount);
                    }
                } else if (status == PaymentStatus.COD_PENDING) {
                    awaiting = awaiting.add(amount);
                }
            } else if (status == PaymentStatus.SUCCESS
                    || status == PaymentStatus.PARTIALLY_REFUNDED
                    || status == PaymentStatus.REFUNDED) {
                // A REFUNDED PAYMENT WAS STILL COLLECTED. The money arrived
                // and then left again; dropping it here would make the
                // collected total disagree with gross sales for any window
                // containing a refund, and the refund line beside it is
                // already where the money going back is reported.
                online = online.add(amount);
            }
        }

        long orderCount = orders.countOrdersBetween(from, to);
        long cancelled = orders.countCancelledBetween(from, to);

        return new Statement(window, from, to,
                gross, refunded, gross.subtract(refunded),
                online, cash, codUpi, awaiting,
                orderCount, cancelled);
    }

    /**
     * One line per shop, for a marketplace operator.
     *
     * CROSS-SHOP ON PURPOSE, and it is the caller that makes that legitimate:
     * PlatformMerchantController requires PERM_PLATFORM_ADMIN, which is the
     * only credential TenantResolver answers with a platform scope. Called
     * under a shop scope - which is what would happen if that gate were ever
     * removed - the shopId below is that shop's, and the operator sees one
     * row: their own. It fails closed rather than open.
     */
    public record ShopLine(Long shopId, long orderCount, long cancelledCount,
                           BigDecimal grossSales, BigDecimal refunds, BigDecimal netSales,
                           LocalDateTime lastOrderAt) {}

    @Transactional(readOnly = true)
    public List<ShopLine> byShop(int days) {
        int window = Math.max(1, Math.min(days, MAX_PERIOD_DAYS));
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(window);
        Long shopId = TenantContext.reportingShopId();

        java.util.Map<Long, BigDecimal> refundedByShop = new java.util.HashMap<>();
        for (Object[] row : refunds.settledByShopBetween(from, to, shopId)) {
            refundedByShop.put(asLong(row[0]), orZero((BigDecimal) row[1]));
        }

        List<ShopLine> lines = new java.util.ArrayList<>();
        for (Object[] row : orders.tradingByShopBetween(from, to, shopId)) {
            Long shop = asLong(row[0]);
            BigDecimal gross = orZero((BigDecimal) row[2]);
            BigDecimal refunded = refundedByShop.getOrDefault(shop, BigDecimal.ZERO);
            lines.add(new ShopLine(shop,
                    asLong(row[1]),
                    asLong(row[3]),
                    gross,
                    refunded,
                    gross.subtract(refunded),
                    (LocalDateTime) row[4]));
        }
        lines.sort(java.util.Comparator.comparing(ShopLine::grossSales).reversed());
        return lines;
    }

    /** Order status counts for the shop in scope - "what needs attention now". */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> openWorkForCurrentShop() {
        java.util.Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        for (Object[] row : orders.countByStatus()) {
            byStatus.put(row[0].toString(), asLong(row[1]));
        }
        return byStatus;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
