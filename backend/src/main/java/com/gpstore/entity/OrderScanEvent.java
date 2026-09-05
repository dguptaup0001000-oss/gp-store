package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.TenantEntityListener;
import com.gpstore.platform.ShopScopeFilter;
import org.hibernate.annotations.Filter;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One scan attempt, successful or not.
 *
 * REJECTED SCANS ARE RECORDED TOO, and they are the more interesting half.
 * A rejection is a worker standing at the counter being told no, and the
 * reason separates "the territory map is wrong" from "someone else already
 * took it" from "this label is from a cancelled order". Storing only successes
 * would leave the system silent exactly when somebody is asking why.
 *
 * THE ZONE AND SUBZONE CODES ARE COPIES, not joins. This is a record of what
 * was true at 12:42:18. A territory renamed or redrawn next month must not
 * silently rewrite last month's history - the whole point of an audit row is
 * that it stops being editable the moment it is written.
 */
@Entity
@Table(name = "order_scan_events")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class OrderScanEvent implements ShopOwned {
    // ------------------------------------------------------- which shop
    //
    // Written once, at insert time, by TenantEntityListener - never by a
    // request. Read back through the "shopScope" filter (see the @Filter
    // above), which Hibernate turns into an extra "and shop_id = ?" on
    // every query against this table while a shop scope is active.
    //
    // Nullable in the column definition only because V46 added it to
    // tables that already had rows; every row is backfilled and the
    // migration refuses to complete otherwise.
    @Column(name = "shop_id")
    private Long shopId;


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when the scanned token matched no order at all. */
    @Column(name = "order_id")
    private Long orderId;

    /** Copied so the audit reads without a join, and survives an order purge. */
    @Column(name = "order_number", length = 64)
    private String orderNumber;

    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "worker_name", length = 120)
    private String workerName;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 32)
    private String outcome;

    /** In the words the worker was shown, so the two can never disagree. */
    @Column(length = 500)
    private String reason;

    @Column(name = "zone_code", length = 16)
    private String zoneCode;

    @Column(name = "subzone_code", length = 16)
    private String subzoneCode;

    @Column(name = "scanned_at", nullable = false)
    private LocalDateTime scannedAt;

    /**
     * The app's own id for this attempt, unique per worker.
     *
     * A worker on a weak connection taps scan, sees nothing, and taps again.
     * Both requests carry this same value, a unique index refuses the second
     * insert, and the service replays the first result rather than recording a
     * second scan and sending a second notification.
     */
    @Column(name = "client_request_id", length = 80)
    private String clientRequestId;

    @Column(name = "performed_by_admin")
    private Boolean performedByAdmin = Boolean.FALSE;

    @PrePersist
    void normalise() {
        // Hibernate binds an explicit NULL for an unset field rather than
        // omitting the column, so the database DEFAULT never applies.
        if (performedByAdmin == null) {
            performedByAdmin = Boolean.FALSE;
        }
        if (scannedAt == null) {
            scannedAt = LocalDateTime.now();
        }
    }
}
