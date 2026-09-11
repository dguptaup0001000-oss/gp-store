package com.gpstore.order.cancellation;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a customer still owes ONE shop for a cancellation it could not charge.
 *
 * <p>§11: a COD order collected no money, so a cancellation charge on one has
 * nothing to come out of. Deducting it from a refund that does not exist is
 * not possible and inventing a payment to reverse would be worse, so the
 * charge becomes a debt that the customer is shown and that is added to a
 * later order at the same shop.
 *
 * <p>SHOP-OWNED, and that is the point. The debt is to a shop - it was that
 * shop's packing time and that shop's stock that were tied up - not to
 * GP-STORE. Shop B must not be able to collect it, and must not be able to
 * see it: a competitor reading "this customer cancels a lot" off another
 * shop's ledger is exactly the cross-tenant leak §6 exists to prevent.
 *
 * <p>ONE ROW PER CANCELLED ORDER, enforced by a unique index rather than by
 * remembering to check: a retried cancellation must not bill the customer
 * twice for changing their mind once.
 */
@Entity
@Table(name = "customer_cancellation_dues")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class CustomerCancellationDue implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    @Override
    public Long getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    /** Who owes it. A plain id: the customer is the platform's, not the shop's. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The cancellation that created the debt. */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DueStatus status = DueStatus.OUTSTANDING;

    /** The order it was finally collected on, once one exists. */
    @Column(name = "settled_order_id")
    private Long settledOrderId;

    /** In the shop's own words, shown to the customer with the amount. */
    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "waived_by", length = 120)
    private String waivedBy;

    public static CustomerCancellationDue of(
            Long customerId, Long orderId, BigDecimal amount, String reason) {
        CustomerCancellationDue due = new CustomerCancellationDue();
        due.customerId = customerId;
        due.orderId = orderId;
        due.amount = amount;
        due.reason = reason;
        due.status = DueStatus.OUTSTANDING;
        due.createdAt = LocalDateTime.now();
        return due;
    }

    public boolean isOutstanding() {
        return status == DueStatus.OUTSTANDING;
    }
}
