package com.gpstore.entity;

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
public class DeliveryPricingSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    // ---- distance ----------------------------------------------------

    /** Flat charge for anything up to {@link #distanceTier1MaxKm}. */
    private BigDecimal distanceTier1Charge = new BigDecimal("5.00");
    private BigDecimal distanceTier1MaxKm = new BigDecimal("1.00");

    /** Flat charge above tier 1 and up to {@link #distanceTier2MaxKm}. */
    private BigDecimal distanceTier2Charge = new BigDecimal("10.00");
    private BigDecimal distanceTier2MaxKm = new BigDecimal("2.00");

    /** Added per whole kilometre beyond tier 2, rounding the distance up. */
    private BigDecimal additionalKmCharge = new BigDecimal("5.00");

    // ---- weight ------------------------------------------------------

    private BigDecimal freeWeightKg = new BigDecimal("10.000");
    private BigDecimal additionalWeightPerKg = new BigDecimal("2.00");
    private BigDecimal maximumWeightSurcharge = new BigDecimal("20.00");

    // ---- margin ------------------------------------------------------

    /** Free delivery needs profit >= this x the normal charge. */
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
    private BigDecimal roadDistanceFactor = new BigDecimal("1.000");

    /**
     * What one piece-counted item is assumed to weigh.
     *
     * Zero by default, and that is the safe direction: a fabricated weight
     * charges a real customer for a number nobody measured. The quote names
     * every item that fell back to this, so filling in real weights is a list
     * to work through rather than a mystery.
     */
    private BigDecimal assumedWeightPerItemKg = new BigDecimal("0.000");

    private LocalDateTime updatedAt;
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
