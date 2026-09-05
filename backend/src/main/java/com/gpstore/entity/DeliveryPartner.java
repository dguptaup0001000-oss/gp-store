package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.TenantEntityListener;
import com.gpstore.platform.ShopScopeFilter;
import org.hibernate.annotations.Filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_partners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class DeliveryPartner implements ShopOwned {
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

    private String name;

    private String mobile;

    private String vehicleType;

    private String vehicleNumber;

    private Boolean available;

    private Boolean active;

    // Live GPS position, pushed by the partner's own app every few seconds
    // while on a run. Null until their first location update - never assume
    // these are populated, especially for a partner who has never gone on
    // a run yet.
    private Double currentLatitude;

    private Double currentLongitude;

    private LocalDateTime locationUpdatedAt;

    // Links this operational roster record to the real Customer account
    // (role=DELIVERY_BOY) that lets this person actually log in - a
    // DeliveryPartner row alone was never a login identity, just a name on
    // a list. Hidden from JSON: this is an internal link, not something the
    // Flutter admin screens need to render.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_customer_id")
    @JsonIgnore
    private Customer account;

    // ---------------------------------------------------------- the login
    //
    // THE WORKER'S CREDENTIALS LIVE HERE, not on a Customer row. Signing in
    // to the worker app reads this record and nothing else - no customer
    // lookup, no role, no account link. That is what lets one address be an
    // administrator, a shopper and a rider at the same time: three separate
    // credentials that cannot collide with each other.

    /** What they type in. Matched case-insensitively; unique among live workers. */
    private String loginEmail;

    /**
     * BCrypt output. Never the password, and never serialised - @JsonIgnore
     * so no admin screen, error body or log line can carry it out of here.
     */
    @JsonIgnore
    private String passwordHash;

    /**
     * Barred until this moment; null or past means they may sign in.
     *
     * A TIMESTAMP RATHER THAN A FLAG, because "closed for an hour" has to end
     * by itself. A boolean somebody must remember to clear is a worker locked
     * out all weekend because whoever set it went home.
     */
    private LocalDateTime suspendedUntil;

    /** Shown to them at the login screen, so a bar is never unexplained. */
    private String suspensionReason;

    /**
     * Soft delete. Deliveries reference this row, so erasing it would leave
     * finished orders with no rider. Set this and the worker disappears from
     * the roster, from dispatch and from the login screen, while the shop's
     * own history stays readable.
     */
    private LocalDateTime deletedAt;
}
