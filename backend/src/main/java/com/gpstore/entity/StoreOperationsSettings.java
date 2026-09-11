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

    /**
     * When a pause ends by itself, or null for one that does not.
     *
     * <p>THE HALF THAT WAS MISSING FROM "OFF". A shopkeeper stepping out for
     * thirty minutes had to choose between leaving the shop taking orders they
     * cannot pack and closing it - and then discovering at nine that evening
     * that nobody had reopened it. OFF with a time is "back shortly"; OFF
     * without one is "closed until we say otherwise", which is still a real
     * thing a shop needs to be able to say.
     *
     * <p>NOTHING RESUMES IT. There is no scheduled job, because there does not
     * need to be one: {@link #effectiveAcceptance} compares the clock, so a
     * pause that has run out is already over the next time anybody asks. A job
     * would be a second place that could disagree with the first, and it would
     * be wrong for exactly as long as it was late.
     *
     * <p>Stored as a timestamp rather than a duration so that "until 16:00"
     * survives a restart, a reread and a second server.
     */
    @Column(name = "paused_until")
    private LocalDateTime pausedUntil;

    /**
     * How long after ordering it costs nothing to cancel (§9).
     *
     * <p>THE FIVE-SECOND COUNTDOWN, MOVED SOMEWHERE IT CANNOT BE LOST. It
     * used to exist only as a timer on a screen, which meant it was a promise
     * the client made and the server knew nothing about - a request arriving
     * a moment late, or from a build with a different timer, got whatever the
     * cancellation path happened to do. It is now the shop's own setting,
     * defaulted to the five seconds §9 says must remain, and the server that
     * takes the money is the thing that honours it.
     *
     * <p>A shop may widen it. None may remove it below zero, and V59 bounds
     * it at an hour - past that it is not a countdown, it is a policy.
     */
    @Column(name = "free_cancellation_seconds", nullable = false)
    private Integer freeCancellationSeconds = 5;

    /**
     * What this shop charges to cancel after that window, or null for nothing.
     *
     * <p>THE MERCHANT'S NUMBER (§10). GP-STORE caps it - see
     * CancellationPolicy - but does not set it, and the default is null
     * because a fee nobody chose is a charge nobody agreed to.
     */
    @Column(name = "cancellation_fee_percent", precision = 5, scale = 2)
    private java.math.BigDecimal cancellationFeePercent;

    /**
     * Whether the fee also applies to what was paid for delivery.
     *
     * <p>Off unless a shop turns it on: the delivery charge is money paid for
     * a journey that did not happen. A shop whose rider was already out has a
     * real case for it, which is why the switch exists at all.
     */
    @Column(name = "cancellation_charges_delivery", nullable = false)
    private Boolean cancellationChargesDelivery = Boolean.FALSE;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    /** Never null, whatever the row says. A null switch must not read as "off". */
    public StoreOrderAcceptance acceptanceOrDefault() {
        return orderAcceptance == null ? StoreOrderAcceptance.AUTO : orderAcceptance;
    }

    /**
     * The switch as it stands AT AN INSTANT, with an expired pause already
     * lifted.
     *
     * <p>Every read of the acceptance state on the order path goes through
     * here rather than through {@link #acceptanceOrDefault}, so a shop that
     * paused for half an hour is taking orders again half an hour later
     * without anybody touching the row. The row is left alone deliberately:
     * rewriting it on a read would turn every status check into a write, and
     * the stored value is still the truthful record of what the shopkeeper
     * chose.
     *
     * @param at the moment being asked about - the caller's, never this
     *           method's, for the same reason getStoreStatusAt takes one
     */
    public StoreOrderAcceptance effectiveAcceptance(java.time.LocalDateTime at) {
        StoreOrderAcceptance stated = acceptanceOrDefault();
        if (stated != StoreOrderAcceptance.OFF || pausedUntil == null || at == null) {
            return stated;
        }
        return at.isBefore(pausedUntil) ? StoreOrderAcceptance.OFF : StoreOrderAcceptance.AUTO;
    }

    /** The moment this pause lifts, or null when it is not a timed pause. */
    public LocalDateTime pauseEndsAt(java.time.LocalDateTime at) {
        if (acceptanceOrDefault() != StoreOrderAcceptance.OFF || pausedUntil == null) {
            return null;
        }
        return at != null && at.isBefore(pausedUntil) ? pausedUntil : null;
    }
}
