package com.gpstore.entity;

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
public class StoreOperationsSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

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
