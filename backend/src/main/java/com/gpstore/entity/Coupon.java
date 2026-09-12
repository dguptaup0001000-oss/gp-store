package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.TenantEntityListener;
import com.gpstore.platform.ShopScopeFilter;
import org.hibernate.annotations.Filter;

import com.gpstore.enums.DiscountType;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "coupons")
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class Coupon implements Serializable, ShopOwned {
    // ------------------------------------------------------- which shop
    //
    // Written once, at insert time, by TenantEntityListener - never by a
    // request. Read back through the "shopScope" filter (see the @Filter
    // above), which Hibernate turns into an extra "and shop_id = ?" on
    // every query against this table while a shop scope is active.
    //
    // Nullable in the column definition only because V46 added it to
    // tables that already had rows; every row is backfilled and the
    // migration refuses to complete otherwise.
    @Column(name = "shop_id")
    private Long shopId;


    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String couponCode;

    @Enumerated(EnumType.STRING)
    private DiscountType discountType;

    // For FLAT: rupees off merchandise. For PERCENTAGE: whole-number percent
    // (e.g. 10 = 10%). For DELIVERY_FLAT: maximum rupees taken off delivery.
    private BigDecimal discountValue;

    // Only used for PERCENTAGE - caps the discount so "50% off" can't blow out on a big cart.
    private BigDecimal maxDiscountAmount;

    private BigDecimal minimumOrderAmount;

    private LocalDate expiryDate;

    // Null/0 = unlimited uses.
    private Integer usageLimit;

    private Integer usedCount = 0;

    private Boolean active;
}
