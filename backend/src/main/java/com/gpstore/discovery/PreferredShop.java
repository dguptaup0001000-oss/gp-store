package com.gpstore.discovery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One shop a customer has chosen for one category (Part 2 §4).
 *
 * <p>PER CATEGORY, NOT PER CUSTOMER. The kirana somebody trusts for atta has
 * no opinion about screws, and the hardware shop they trust has never sold
 * atta. A single global "preferred shop" is the design that looks obvious and
 * fails the first week.
 *
 * <p>CUSTOMER-OWNED. This is the customer's own list, like their addresses -
 * it is not shop-owned, does not carry the tenancy filter, and a merchant has
 * no route to read who has picked them or who has picked their competitor.
 * The column is {@code preferred_shop_id} rather than {@code shop_id} for
 * exactly that reason: it is the data "which shop", not the boundary "whose
 * row is this".
 *
 * <p>A SLOT, NOT A COUNT. {@link #slot} is 1 or 2, and a unique index on
 * (customer, category, slot) is what enforces "up to two" - a third
 * preference has nowhere to go. A service-layer count would be two concurrent
 * requests away from being three.
 */
@Entity
@Table(name = "customer_preferred_shops")
@Getter
@Setter
@NoArgsConstructor
public class PreferredShop {

    /** First choice and second choice. There is no third. */
    public static final int FIRST_CHOICE = 1;
    public static final int SECOND_CHOICE = 2;
    public static final int MAX_PER_CATEGORY = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "preferred_shop_id", nullable = false)
    private Long preferredShopId;

    @Column(name = "slot", nullable = false)
    private Integer slot;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public static PreferredShop of(Long customerId, Long categoryId, Long shopId, int slot) {
        PreferredShop row = new PreferredShop();
        row.customerId = customerId;
        row.categoryId = categoryId;
        row.preferredShopId = shopId;
        row.slot = slot;
        row.createdAt = LocalDateTime.now();
        return row;
    }
}
