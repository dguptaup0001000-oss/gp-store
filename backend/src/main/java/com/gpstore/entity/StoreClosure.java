package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day the vans do not run: a festival, a wedding, a flood.
 *
 * <p>A DATE, NOT A TIMESTAMP. "Closed on Holi" is a statement about a day in
 * the shop's own calendar, and storing it as an instant would make it start
 * and end at 05:30 local - the classic bug where a holiday begins at half past
 * five in the morning. The date is always interpreted in the shop's zone (see
 * StoreScheduleProperties).
 *
 * <p>CLOSED MEANS NO DELIVERIES, NOT NO SHOP. Browsing continues, orders are
 * still taken, and they are scheduled for the next open day. See StoreStatus
 * for why those are three separate questions.
 *
 * <p>AND IT IS ONE SHOP THAT IS SHUT. Until V54 this table was shared, which
 * meant a merchant closing for a family wedding closed every shop on the
 * marketplace - and, because the closed day was globally unique, the second
 * shop to declare Diwali was told the day was already taken. Being ShopOwned
 * fixes both at once: reads are filtered to the shop in scope and writes are
 * stamped with it, so one kirana's holiday is invisible to the one next door.
 */
@Entity
@Table(name = "store_closures",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_store_closures_shop_day",
                columnNames = {"shop_id", "closed_on"}))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class StoreClosure implements ShopOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id")
    private Long shopId;

    /** Unique WITHIN A SHOP: one kirana cannot close the same day twice, two may. */
    @Column(name = "closed_on", nullable = false)
    private LocalDate closedOn;

    /** Shown to customers, so it is written for them, not for the log. */
    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;
}
