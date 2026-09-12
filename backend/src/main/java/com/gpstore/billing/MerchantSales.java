package com.gpstore.billing;

import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * WHAT A MERCHANT ACTUALLY SOLD, on the terms §8 sets for commission.
 *
 * <p>§8 EXCLUDES THREE THINGS and this is the one place they are excluded, so
 * the fee cannot be computed on a different base somewhere else:
 * <ul>
 *   <li>DELIVERY CHARGES - GP-STORE takes 0% of them. A merchant who delivers
 *       further is not more profitable to the platform, and charging on the
 *       delivery would make every long delivery a worse deal for the shop than
 *       it looks.
 *   <li>REFUNDED AMOUNTS - money that came back was not a sale.
 *   <li>CANCELLED AND REJECTED ORDERS - which is why only DELIVERED and
 *       COMPLETED orders are counted at all.
 * </ul>
 *
 * <p>ACROSS A MERCHANT'S SHOPS, which is what makes this platform work rather
 * than shop work: one merchant may own several kiranas (§4 of Part 1) and
 * their account with GP-STORE is one account. The shop ids are resolved from
 * the merchant and passed explicitly, and the query runs in the PLATFORM
 * scope - so the narrowing is the merchant's own shop list rather than
 * whatever tenant the caller happened to be in. A billing job that inherited a
 * request's shop scope would bill one shop's sales and call it the merchant's.
 */
@Service
public class MerchantSales {

    @PersistenceContext
    private EntityManager entityManager;

    private final ShopRepository shops;

    public MerchantSales(ShopRepository shops) {
        this.shops = shops;
    }

    /**
     * @param completedOrders    orders that reached the customer and were not refunded away
     * @param grossSales         what those orders came to, delivery included
     * @param deliveryCharges    the part GP-STORE takes nothing from
     * @param refunds            what went back to customers
     * @param commissionableSales gross, less delivery, less refunds - the §8 base
     */
    public record Week(long completedOrders, BigDecimal grossSales, BigDecimal deliveryCharges,
                       BigDecimal refunds, BigDecimal commissionableSales,
                       List<Line> lines) {}

    /**
     * One sale, and the part of it commission is charged on.
     *
     * <p>PER ORDER RATHER THAN PER WEEK, and that is not bookkeeping fussiness.
     * §8 requires commission to be REVERSIBLE when a completed order is later
     * refunded, which is only answerable if the charge knew which sale it came
     * from. It is also what a merchant disputing a line needs: not "commission,
     * 240" but the order that produced it.
     */
    public record Line(Long orderId, BigDecimal commissionable) {}

    /** One merchant's week, across every shop they own. */
    @Transactional(readOnly = true)
    public Week forMerchant(Long merchantId, LocalDate from, LocalDate to) {
        List<Long> shopIds = shops.findByMerchantId(merchantId).stream()
                .map(Shop::getId)
                .toList();
        if (shopIds.isEmpty()) {
            return empty();
        }

        // PLATFORM SCOPE, EXPLICIT SHOP LIST. See the class comment: a billing
        // job must not inherit a request's tenant, and the merchant's own
        // shops are the narrowing.
        return TenantContext.runWithin(TenantScope.platform(), () -> {
            Query query = entityManager.createNativeQuery("""
                    SELECT o.id,
                           COALESCE(o.total_amount, 0),
                           COALESCE(o.delivery_fee, 0),
                           COALESCE(r.refunded, 0)
                    FROM orders o
                    LEFT JOIN (
                        SELECT p.order_id AS order_id, SUM(rf.amount) AS refunded
                        FROM refunds rf
                        JOIN payments p ON p.id = rf.payment_id
                        WHERE rf.status = 'SUCCEEDED'
                        GROUP BY p.order_id
                    ) r ON r.order_id = o.id
                    WHERE o.shop_id IN (:shopIds)
                      AND o.order_status IN ('DELIVERED', 'COMPLETED')
                      AND o.order_date >= :from
                      AND o.order_date < :to
                    ORDER BY o.id
                    """);
            query.setParameter("shopIds", shopIds);
            query.setParameter("from", from.atStartOfDay());
            query.setParameter("to", to.plusDays(1).atStartOfDay());

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();

            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal delivery = BigDecimal.ZERO;
            BigDecimal refunds = BigDecimal.ZERO;
            BigDecimal commissionable = BigDecimal.ZERO;
            List<Line> lines = new java.util.ArrayList<>(rows.size());

            for (Object[] row : rows) {
                Long orderId = ((Number) row[0]).longValue();
                BigDecimal orderGross = scaled(row[1]);
                BigDecimal orderDelivery = scaled(row[2]);
                BigDecimal orderRefunds = scaled(row[3]);

                // NEVER NEGATIVE, per order. A sale whose refunds exceed it
                // earns no commission, and it must not earn NEGATIVE
                // commission either - that would be GP-STORE paying a merchant
                // for having been refunded.
                BigDecimal base = orderGross.subtract(orderDelivery).subtract(orderRefunds)
                        .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

                gross = gross.add(orderGross);
                delivery = delivery.add(orderDelivery);
                refunds = refunds.add(orderRefunds);
                commissionable = commissionable.add(base);
                lines.add(new Line(orderId, base));
            }

            return new Week(rows.size(), scaled(gross), scaled(delivery), scaled(refunds),
                    scaled(commissionable), List.copyOf(lines));
        });
    }

    /**
     * The §8 base for a single order, used when a refund arrives later and its
     * commission has to be given back proportionally.
     */
    @Transactional(readOnly = true)
    public BigDecimal commissionableForOrder(Long orderId) {
        return TenantContext.runWithin(TenantScope.platform(), () -> {
            Query query = entityManager.createNativeQuery("""
                    SELECT COALESCE(o.total_amount, 0) - COALESCE(o.delivery_fee, 0)
                    FROM orders o WHERE o.id = :orderId
                    """);
            query.setParameter("orderId", orderId);
            List<?> rows = query.getResultList();
            return rows.isEmpty() ? BigDecimal.ZERO : scaled(rows.get(0));
        });
    }

    private static Week empty() {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new Week(0, zero, zero, zero, zero, List.of());
    }

    private static BigDecimal scaled(Object value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal decimal = value instanceof BigDecimal big
                ? big : new BigDecimal(value.toString());
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }
}
