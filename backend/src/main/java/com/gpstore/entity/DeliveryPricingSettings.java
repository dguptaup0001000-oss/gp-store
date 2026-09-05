package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.ShopScopeFilter;
import com.gpstore.platform.TenantEntityListener;
import org.hibernate.annotations.Filter;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Every number in the delivery price, in one editable row.
 *
 * WHY THIS IS A TABLE. The previous rule lived in application.properties,
 * which meant changing ₹3/km required a redeploy - the shop could not respond
 * to a fuel price or a bad week without waiting for someone with a laptop.
 * Every field here is one a shopkeeper has an opinion about, so every field
 * here is something they can edit.
 *
 * ONE ROW, id = 1, enforced by a check constraint in V21. A settings table
 * with two rows is a settings table that silently applies whichever one the
 * query returned first.
 */
@Entity
@Table(name = "delivery_pricing_settings")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class DeliveryPricingSettings implements java.io.Serializable, ShopOwned {

    /**
     * Serializable because this is cached, and the cache is Redis with JDK
     * serialization (see ProductResponse, which carries the same note). A
     * cached type that is not Serializable does not fail at startup or in any
     * test that misses the cache - it throws NotSerializableException from
     * inside the cache WRITE, on the first request that would have populated
     * it, turning a read path into a 500.
     */
    private static final long serialVersionUID = 1L;

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

    // EVERY COLUMN NAME IS EXPLICIT, and that is not style. Hibernate's
    // naming strategy turns distanceTier1Charge into "distance_tier1charge" -
    // no underscore before "charge", because the splitter does not break
    // between a digit and the word after it. The migration writes
    // "distance_tier1_charge", which reads the way a person would.
    //
    // Under ddl-auto=update those two names silently become TWO COLUMNS and
    // the application quietly uses its own, so nothing fails and the settings
    // an admin edits are not the settings that get read. Under validate it is
    // a startup failure. Naming them here removes the guess entirely.

    // ---- distance ----------------------------------------------------

    /** Flat charge for anything up to {@link #distanceTier1MaxKm}. */
    @Column(name = "distance_tier1_charge")
    private BigDecimal distanceTier1Charge = new BigDecimal("5.00");
    @Column(name = "distance_tier1_max_km")
    private BigDecimal distanceTier1MaxKm = new BigDecimal("1.00");

    /** Flat charge above tier 1 and up to {@link #distanceTier2MaxKm}. */
    @Column(name = "distance_tier2_charge")
    private BigDecimal distanceTier2Charge = new BigDecimal("10.00");
    @Column(name = "distance_tier2_max_km")
    private BigDecimal distanceTier2MaxKm = new BigDecimal("2.00");

    /** Added per whole kilometre beyond tier 2, rounding the distance up. */
    @Column(name = "additional_km_charge")
    private BigDecimal additionalKmCharge = new BigDecimal("5.00");

    // ---- weight ------------------------------------------------------

    @Column(name = "free_weight_kg")

    private BigDecimal freeWeightKg = new BigDecimal("10.000");
    @Column(name = "additional_weight_per_kg")
    private BigDecimal additionalWeightPerKg = new BigDecimal("2.00");
    @Column(name = "maximum_weight_surcharge")
    private BigDecimal maximumWeightSurcharge = new BigDecimal("20.00");

    // ---- margin ------------------------------------------------------

    /** Free delivery needs profit >= this x the normal charge. */
    @Column(name = "free_delivery_multiplier")
    private BigDecimal freeDeliveryMultiplier = new BigDecimal("3.00");

    // ---- honesty knobs -----------------------------------------------

    /**
     * Straight-line kilometres are multiplied by this to approximate road
     * distance when no routing provider is configured.
     *
     * 1.000 means "quote the straight line as measured", which systematically
     * UNDER-charges - no road is straighter than the line between two points.
     * Left at 1.000 rather than set to a plausible-sounding 1.3, because an
     * invented multiplier charges every customer for kilometres nobody
     * measured, and a known under-charge is the better error to have. The
     * quote states which distance it used.
     */
    @Column(name = "road_distance_factor")
    private BigDecimal roadDistanceFactor = new BigDecimal("1.000");

    /**
     * What one piece-counted item is assumed to weigh.
     *
     * Zero by default, and that is the safe direction: a fabricated weight
     * charges a real customer for a number nobody measured. The quote names
     * every item that fell back to this, so filling in real weights is a list
     * to work through rather than a mystery.
     */
    @Column(name = "assumed_weight_per_item_kg")
    private BigDecimal assumedWeightPerItemKg = new BigDecimal("0.000");

    @Column(name = "updated_at")

    private LocalDateTime updatedAt;
    @Column(name = "updated_by")
    private String updatedBy;

    /**
     * Replaces any value that is null or nonsensical with the V1 default.
     *
     * A settings row is edited by a person through an admin screen, and a
     * blank field or a pasted minus sign must not become a negative delivery
     * charge on a customer's checkout. Every field is bounded here rather than
     * trusted, because this object is read on the pricing path where there is
     * nowhere sensible to throw.
     */
    public void normalise() {
        distanceTier1Charge = atLeastZero(distanceTier1Charge, "5.00");
        distanceTier1MaxKm = positive(distanceTier1MaxKm, "1.00");
        distanceTier2Charge = atLeastZero(distanceTier2Charge, "10.00");
        distanceTier2MaxKm = positive(distanceTier2MaxKm, "2.00");
        additionalKmCharge = atLeastZero(additionalKmCharge, "5.00");

        freeWeightKg = atLeastZero(freeWeightKg, "10.000");
        additionalWeightPerKg = atLeastZero(additionalWeightPerKg, "2.00");
        maximumWeightSurcharge = atLeastZero(maximumWeightSurcharge, "20.00");

        freeDeliveryMultiplier = atLeastZero(freeDeliveryMultiplier, "3.00");
        roadDistanceFactor = positive(roadDistanceFactor, "1.000");
        assumedWeightPerItemKg = atLeastZero(assumedWeightPerItemKg, "0.000");

        // Tier 2 must end after tier 1, or "above tier 1 and up to tier 2" is
        // an empty band and every distance past tier 1 falls into the per-km
        // arithmetic with a negative remainder.
        if (distanceTier2MaxKm.compareTo(distanceTier1MaxKm) <= 0) {
            distanceTier2MaxKm = distanceTier1MaxKm.add(BigDecimal.ONE);
        }
    }

    private static BigDecimal atLeastZero(BigDecimal value, String fallback) {
        if (value == null || value.signum() < 0) {
            return new BigDecimal(fallback);
        }
        return value;
    }

    private static BigDecimal positive(BigDecimal value, String fallback) {
        if (value == null || value.signum() <= 0) {
            return new BigDecimal(fallback);
        }
        return value;
    }
}
