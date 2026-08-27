package com.gpstore.entity;

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
public class Coupon implements Serializable {

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
