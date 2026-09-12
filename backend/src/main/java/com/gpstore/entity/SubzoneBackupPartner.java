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
 * A named standing backup for one subzone, in priority order.
 *
 * These are people who have actually ridden this territory - named in advance
 * by someone who knows that, not computed at dispatch time from a distance
 * formula. That is the difference between "the nearest available rider" and
 * "the rider who already knows which gate opens", and it is why the absence
 * ladder tries this list before it tries geography.
 */
@Entity
@Table(name = "subzone_backup_partners")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class SubzoneBackupPartner implements ShopOwned {

    /**
     * WHOSE ARRANGEMENT THIS IS (W4).
     *
     * A named backup is "when Z7B's rider is off, ask Ramesh" - a standing
     * agreement inside one shop's roster. Filtered here rather than left to
     * the subzone's filter, because this row is the ROOT of its own queries
     * and Hibernate's filter does not follow a join to reach the subzone's.
     */
    @Column(name = "shop_id")
    private Long shopId;



    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subzone_id", nullable = false)
    private DeliverySubzone subzone;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "partner_id", nullable = false)
    private DeliveryPartner partner;

    /** 1 is tried first. Ties break on id, so the order is always total. */
    @Column(nullable = false)
    private Integer priority = 1;

    @PrePersist
    @PreUpdate
    void normalise() {
        if (priority == null || priority < 1) {
            priority = 1;
        }
    }
}
