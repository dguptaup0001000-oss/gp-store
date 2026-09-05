package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One of the 8 main geographic zones the delivery area is divided into.
 *
 * A main zone is a real place with real edges - the side of the canal, the
 * stretch the bypass cuts off, the colonies the flyover feeds. It is NOT one
 * eighth of a circle, and nothing in this application will ever try to make
 * it one. There is no code anywhere that redraws a zone, splits one because
 * it got busy, or balances two against each other; the only thing that moves
 * a boundary is an administrator deciding to move it.
 *
 * The zone itself carries no geometry. Its shape is the union of its
 * subzones' boundaries, which is the only definition that cannot drift out of
 * agreement with them.
 */
@Entity
@Table(name = "delivery_zones")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class DeliveryZone implements ShopOwned {

    /**
     * WHOSE MAP THIS IS (W4).
     *
     * A zone is one shop's idea of how its delivery area divides, agreed with
     * its own riders. Two kiranas on the same street will draw different lines
     * and both will be right; a shared map would force one shopkeeper's
     * boundaries on the other, and hand whichever shop resolved an address
     * first the right to pick a rider for a competitor's order.
     *
     * Stamped by TenantEntityListener at insert, read back through the filter.
     * Never sent by a request.
     */
    @Column(name = "shop_id")
    private Long shopId;



    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What dispatchers and riders say out loud: Z1 ... Z8. Unique. */
    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /**
     * Why this boundary is where it is, in the words of whoever drew it:
     * "everything north of the canal that the Station Road bridge reaches".
     *
     * This is the first thing lost and the most expensive to reconstruct. Six
     * months on, nobody remembers whether a boundary follows a road because
     * the road is fast or because the road is impassable, and the difference
     * decides whether merging two territories is sensible or catastrophic.
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @PrePersist
    @PreUpdate
    void normalise() {
        if (active == null) {
            active = Boolean.TRUE;
        }
        if (code != null) {
            code = code.trim().toUpperCase();
        }
    }
}
