package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import org.hibernate.annotations.Filter;

import com.gpstore.store.StoreOrderAcceptance;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The owner's switch on order acceptance, and nothing else.
 *
 * <p>ONE ROW, id = 1, enforced by a check constraint in V33 - a settings table
 * with two rows is one that silently applies whichever the query returned
 * first. Same shape as {@link DeliveryPricingSettings}, deliberately: a second
 * pattern for the same job is a second place to get it wrong.
 *
 * <p>THE HOURS ARE NOT HERE. 09:00 and 21:00 live in StoreScheduleProperties
 * because they are deployment configuration, not something the shop edits
 * between orders. What the shop edits between orders is exactly one thing:
 * whether to take orders at all right now.
 */
@Entity
@Table(name = "store_operations_settings")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class StoreOperationsSettings implements ShopOwned {

    /**
     * NOT A SINGLETON ANY MORE. This used to be a row pinned to id 1 by a
     * database CHECK, because there was one shop and therefore one answer to
     * "are you taking orders" and "what do you charge to deliver". There is
     * now one row per shop (V49), the id is handed out by the database, and
     * the row is found by the shop in scope rather than by a constant.
     *
     * The constant survives only so that a caller written against the old
     * shape fails to compile rather than silently reading shop #1's settings.
     *
     * @deprecated look the row up by shop - see the service that owns it.
     */
    @Deprecated(forRemoval = true)
    public static final long SINGLETON_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Whose settings these are.
     *
     * Stamped on insert and filtered on read by the Slice 1 machinery, like
     * every other shop-owned row - which is what makes one merchant unable to
     * read or change another's delivery pricing.
     */
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

    /**
     * AUTO unless somebody deliberately changed it.
     *
     * <p>Stored as a string, and V33 constrains the column to the three enum
     * values - see Role/V32 for what happens when an @Enumerated(STRING)
     * column outgrows the check constraint Hibernate generated for it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_acceptance", nullable = false, length = 20)
    private StoreOrderAcceptance orderAcceptance = StoreOrderAcceptance.AUTO;

    /**
     * What to tell customers while orders are off, in the shop's own words.
     *
     * <p>Shown to customers, so it is theirs to write: "Back at 9am" reads
     * very differently from a generic "temporarily unavailable".
     */
    @Column(name = "closure_message", length = 300)
    private String closureMessage;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    /** Never null, whatever the row says. A null switch must not read as "off". */
    public StoreOrderAcceptance acceptanceOrDefault() {
        return orderAcceptance == null ? StoreOrderAcceptance.AUTO : orderAcceptance;
    }
}
