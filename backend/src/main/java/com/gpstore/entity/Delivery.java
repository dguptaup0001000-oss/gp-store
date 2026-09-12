package com.gpstore.entity;

import com.gpstore.platform.ShopOwned;
import com.gpstore.platform.TenantEntityListener;
import com.gpstore.platform.ShopScopeFilter;
import org.hibernate.annotations.Filter;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries")
@Filter(name = ShopScopeFilter.NAME, condition = ShopScopeFilter.CONDITION)
@EntityListeners(TenantEntityListener.class)
public class Delivery implements ShopOwned {
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

    // Deliberately left EAGER: DeliveryController exposes raw Delivery/List<Delivery>
    // entities with no @Transactional on most endpoints (assignDelivery, getAllDeliveries,
    // getDeliveryById, updateDeliveryStatus, getBreachedDeliveries, etc.). LAZY here would
    // throw LazyInitializationException on those. Revisit alongside a DTO refactor of
    // DeliveryController, not in isolation.
    @OneToOne(fetch = FetchType.LAZY)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    private DeliveryBatch batch;

    private Double distanceKm;

    private LocalDateTime estimatedDeliveryTime;

    private String deliveryStatus;

    private String deliveryPersonName;

    private String deliveryPersonPhone;

    private LocalDateTime assignedAt;

    private LocalDateTime deliveredAt;

    private Boolean active;

    // True once this delivery has missed its own estimatedDeliveryTime -
    // either detected in real time when marked DELIVERED late, or by the
    // periodic check for deliveries still in transit past their ETA.
    // No auto-refund/compensation is attached to this - it's a review flag
    // for you, by design (see DeliveryService).
    private Boolean guaranteeBreached = false;

    /**
     * The permanent territory this delivery belonged to, copied from the
     * address at assignment time.
     *
     * Copied rather than read through the order's address on purpose: the
     * address may be edited or re-resolved later, and the question this column
     * answers - "which territory was this delivered in" - is about the past
     * and must not change afterwards.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subzone_id")
    private DeliverySubzone subzone;

    /** Which rung of the dispatch ladder produced this rider. */
    // Not nullable = false, for the same reason as Address.subzoneLocked: a
    // column added to a populated table cannot be created NOT NULL by
    // ddl-auto, and a silently-skipped ALTER leaves it absent altogether.
    // V19 tightens it; normaliseAssignment() below keeps it satisfied.
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_reason", length = 32)
    private AssignmentReason assignmentReason = AssignmentReason.PRIMARY;

    @PrePersist
    @PreUpdate
    void normaliseAssignment() {
        // Hibernate binds an explicit NULL for an unset field rather than
        // omitting the column, so the database DEFAULT never fires on insert.
        if (assignmentReason == null) {
            assignmentReason = AssignmentReason.PRIMARY;
        }
    }

    public DeliverySubzone getSubzone() {
        return subzone;
    }

    public void setSubzone(DeliverySubzone subzone) {
        this.subzone = subzone;
    }

    public AssignmentReason getAssignmentReason() {
        return assignmentReason;
    }

    public void setAssignmentReason(AssignmentReason assignmentReason) {
        this.assignmentReason = assignmentReason;
    }

    public Boolean getGuaranteeBreached() {
        return guaranteeBreached;
    }

    public void setGuaranteeBreached(Boolean guaranteeBreached) {
        this.guaranteeBreached = guaranteeBreached;
    }

    public Delivery() {
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public DeliveryBatch getBatch() {
        return batch;
    }

    public void setBatch(DeliveryBatch batch) {
        this.batch = batch;
    }

    public Double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(Double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public LocalDateTime getEstimatedDeliveryTime() {
        return estimatedDeliveryTime;
    }

    public void setEstimatedDeliveryTime(LocalDateTime estimatedDeliveryTime) {
        this.estimatedDeliveryTime = estimatedDeliveryTime;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getDeliveryPersonName() {
        return deliveryPersonName;
    }

    public void setDeliveryPersonName(String deliveryPersonName) {
        this.deliveryPersonName = deliveryPersonName;
    }

    public String getDeliveryPersonPhone() {
        return deliveryPersonPhone;
    }

    public void setDeliveryPersonPhone(String deliveryPersonPhone) {
        this.deliveryPersonPhone = deliveryPersonPhone;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    @Override
    public Long getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }
}