package com.gpstore.territory;

import com.gpstore.entity.DeliverySubzone;
import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * Which of ONE SHOP's territories a house sits in, and whether a person put it
 * there by hand.
 *
 * <p>WHY THIS IS A TABLE AND NOT TWO COLUMNS ON THE ADDRESS. It used to be
 * {@code addresses.subzone_id} and {@code addresses.subzone_locked}: one
 * answer, on a row that belongs to the CUSTOMER, to a question that has one
 * answer per shop. A customer who buys from two kiranas has one home and two
 * shops' maps over it, so the single column could only ever hold one shop's
 * answer - and the shops took it from each other. Whoever saved the address,
 * re-resolved their map or pinned the row last won; the pin flag froze the
 * address for every other shop's re-resolve; and a shop that read a stamp
 * belonging to somebody else got nothing back (the subzone is shop-owned, so
 * the filter hides it) and quietly resolved live, which is the drift stamping
 * exists to prevent.
 *
 * <p>SHOP-OWNED, so none of that needs a rule of its own: the tenant filter
 * narrows every read to the shop asking, exactly as it does for the territory
 * itself.
 *
 * <p>PERMANENCE IS STILL THE POINT. A rider learns Z7B by delivering to the
 * same houses week after week, and that only pays off if those houses stay in
 * Z7B. A stamp is written when a shop first has a reason to know - the address
 * is saved in that shop's context, the shop dispatches to it, or an
 * administrator pins it - and then nothing automatic moves it again. A
 * boundary edit does not reshuffle anybody; only a deliberate re-resolve
 * does, and only for the shop that asked for it.
 */
@Entity
@Table(name = "address_territory_stamps",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_address_territory_stamp",
                columnNames = {"shop_id", "address_id"}),
        indexes = @jakarta.persistence.Index(
                name = "idx_address_stamp_shop_address",
                columnList = "shop_id, address_id"))
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class AddressTerritoryStamp implements ShopOwned {

    /** Whose map this answer is on. */
    @Column(name = "shop_id")
    private Long shopId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "address_id", nullable = false)
    private Long addressId;

    /**
     * The territory, or null for "this shop looked and found none".
     *
     * <p>EAGER, and safely so: it is this shop's own subzone by construction -
     * the row is shop-owned and so is the subzone, and both are narrowed by
     * the same filter. That is exactly what the old association on Address
     * could not promise, which is why it had to stay lazy and be read through
     * a filtered query instead.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subzone_id")
    private DeliverySubzone subzone;

    /** A person placed this address by hand, for this shop. */
    @Column(nullable = false)
    private Boolean locked = Boolean.FALSE;

    /**
     * The coordinates this answer was resolved from.
     *
     * <p>A stamp is about a POINT, not about a row id. When the customer moves
     * their pin, every shop's stamp is suddenly about somewhere else - and each
     * shop discovers that from its own row, rather than depending on whichever
     * shop happened to handle the edit to go and tell the others. Null means a
     * stamp made before this column existed; it is trusted rather than thrown
     * away, because a migration must not put every customer back into
     * fallback dispatch.
     */
    @Column(name = "stamped_latitude")
    private Double stampedLatitude;

    @Column(name = "stamped_longitude")
    private Double stampedLongitude;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AddressTerritoryStamp() {
    }

    public AddressTerritoryStamp(Long addressId, DeliverySubzone subzone, boolean locked) {
        this.addressId = addressId;
        this.subzone = subzone;
        this.locked = locked;
    }

    public AddressTerritoryStamp(Long addressId, DeliverySubzone subzone, boolean locked,
                                 Double latitude, Double longitude) {
        this(addressId, subzone, locked);
        this.stampedLatitude = latitude;
        this.stampedLongitude = longitude;
    }

    /**
     * Whether this answer is about where the house is now.
     *
     * <p>A stamp with no recorded point is trusted - see the field. Otherwise
     * the comparison is exact, because these are the same two doubles the
     * resolver was handed: a pin that has not been touched round-trips
     * unchanged, and one the customer has moved does not.
     */
    public boolean describes(Double latitude, Double longitude) {
        if (stampedLatitude == null && stampedLongitude == null) {
            return true;
        }
        return java.util.Objects.equals(stampedLatitude, latitude)
                && java.util.Objects.equals(stampedLongitude, longitude);
    }

    public void resolvedAt(Double latitude, Double longitude) {
        this.stampedLatitude = latitude;
        this.stampedLongitude = longitude;
    }

    public Double getStampedLatitude() {
        return stampedLatitude;
    }

    public Double getStampedLongitude() {
        return stampedLongitude;
    }

    @PrePersist
    void stampCreated() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (locked == null) {
            locked = Boolean.FALSE;
        }
    }

    @PreUpdate
    void stampUpdated() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public Long getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Long getId() {
        return id;
    }

    public Long getAddressId() {
        return addressId;
    }

    public void setAddressId(Long addressId) {
        this.addressId = addressId;
    }

    public DeliverySubzone getSubzone() {
        return subzone;
    }

    public void setSubzone(DeliverySubzone subzone) {
        this.subzone = subzone;
    }

    public boolean isLocked() {
        return Boolean.TRUE.equals(locked);
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
