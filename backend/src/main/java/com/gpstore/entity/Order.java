package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import java.math.BigDecimal;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentStatus;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderNumber;

    // Hidden from JSON - same reason as Address.customer: the full Customer
    // object (including their cart, etc.) has no business being nested in
    // every order response. Controllers use an explicit DTO instead (see
    // OrderDetailResponse) for whatever customer info an order response
    // actually needs.
    // DTO refactor complete: all service methods touching order.getCustomer()
    // now carry @Transactional / @Transactional(readOnly = true).
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    // DTO refactor complete: getAllOrders/updateOrderStatus/cancelOrder now
    // return DTOs from @Transactional-covered service methods.
    @ManyToOne(fetch = FetchType.LAZY)
    private Address address;

    // Without the ManagedReference/BackReference pair below (see
    // OrderItem.order), serializing this would infinite-loop: Order ->
    // orderItems -> OrderItem.order -> Order -> orderItems -> ... This was a
    // real, previously-uncaught bug - the admin GET /api/orders endpoint
    // already returns raw Order entities and would have crashed the moment
    // it was called with any order that actually had items.
    @OneToMany(mappedBy = "order")
    @JsonManagedReference
    private List<OrderItem> orderItems;

    private BigDecimal totalAmount;

    private String appliedCouponCode;

    private BigDecimal discountAmount;

    private BigDecimal deliveryFee;

    private Boolean freeDeliveryApplied;

@Enumerated(EnumType.STRING)
private OrderStatus orderStatus;

@Enumerated(EnumType.STRING)
private PaymentStatus paymentStatus;

    private LocalDateTime orderDate;

    private Boolean active;

    /**
     * Exactly-once guard for giving this order's reserved stock back.
     *
     * Three independent paths can decide an order's stock should be
     * returned - an explicit cancellation, the stale-UPI expiry scheduler,
     * and a failed/refunded payment - and they don't coordinate through
     * order status alone. cancelOrder() left a PENDING UPI payment
     * untouched, so a cancelled order's payment stayed eligible for the
     * expiry sweep, which then restored the same stock a second time and
     * silently inflated inventory.
     *
     * Statuses can't express this safely on their own: they answer "what
     * happened to the order", not "has the stock already gone back". This
     * flag answers exactly that one question, and every restore path now
     * checks and sets it while holding the order row lock, so whichever
     * path arrives first wins and the rest become no-ops.
     *
     * Mapped without nullable=false on purpose. The NOT NULL and DEFAULT
     * FALSE live in V7's migration, where they belong; declaring them here
     * too would make ddl-auto=update (which CI and local dev still use) try
     * to ADD COLUMN ... NOT NULL against a table that already has rows,
     * which Postgres rejects and Hibernate then swallows - leaving the
     * column absent and every insert failing. Java-side the field defaults
     * to false and every read goes through Boolean.TRUE.equals(...), so a
     * null from a pre-migration row is treated as "not yet restored", which
     * is the safe interpretation.
     */
    @Column(name = "inventory_restored")
    private Boolean inventoryRestored = false;

    // ------------------------------------------------------------------
    // Worker pack-scan
    // ------------------------------------------------------------------

    /**
     * The opaque token printed on the packed order's QR label.
     *
     * DELIBERATELY MEANINGLESS. It carries no customer name, phone, address,
     * amount or payment state - anyone who photographs a label off a discarded
     * carton learns nothing, because everything the worker's app displays comes
     * back from an authenticated call. The token's only job is to name one
     * order to a server that already knows who is asking.
     *
     * Single use: {@link #qrTokenUsedAt} is stamped by the first successful
     * scan, and a token that has been used is refused. That is what makes
     * "another worker cannot take an order that is already taken" a fact about
     * the database rather than a hope about the app.
     */
    @Column(name = "qr_token", length = 64)
    private String qrToken;

    @Column(name = "qr_token_issued_at")
    private LocalDateTime qrTokenIssuedAt;

    @Column(name = "qr_token_used_at")
    private LocalDateTime qrTokenUsedAt;

    /**
     * The worker who scanned this order and is now accountable for it.
     *
     * Denormalised onto the order rather than read from the scan history,
     * because "who has GP125" is asked on every row of every admin list and
     * answering it through the audit table would be a subquery per order.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "packed_by_partner_id")
    private DeliveryPartner packedByPartner;

    @Column(name = "packed_at")
    private LocalDateTime packedAt;

    /**
     * An administrator's explicit "this worker may take this order", which
     * outranks every territory rule.
     *
     * The escape hatch for the day the map is wrong: the primary is absent and
     * unrostered, a worker is already out at the far village, a subzone has not
     * been drawn yet. Without it the only way to unblock a real order at a real
     * counter would be to edit the territory map, which is permanent and
     * affects every future order - a very large lever for a very small problem.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_worker_partner_id")
    private DeliveryPartner assignedWorkerPartner;

    // ------------------------------------------------------------------
    // Delivery pricing breakdown
    // ------------------------------------------------------------------
    // STORED, NOT RECOMPUTED. A quote is a statement made at a moment: the
    // pricing settings can be edited tomorrow and a variant's cost price can
    // change, so an admin screen that recalculated would show a number this
    // customer was never charged. These columns ARE the record of what was
    // decided and why - every line the admin breakdown shows.

    @Column(name = "delivery_distance_km", precision = 10, scale = 3)
    private BigDecimal deliveryDistanceKm;

    @Column(name = "delivery_weight_kg", precision = 10, scale = 3)
    private BigDecimal deliveryWeightKg;

    @Column(name = "delivery_distance_charge", precision = 10, scale = 2)
    private BigDecimal deliveryDistanceCharge;

    @Column(name = "delivery_weight_charge", precision = 10, scale = 2)
    private BigDecimal deliveryWeightCharge;

    /** What delivery costs before any margin subsidy. */
    @Column(name = "delivery_normal_charge", precision = 10, scale = 2)
    private BigDecimal deliveryNormalCharge;

    /** The margin that was available to spend on delivery. */
    @Column(name = "delivery_order_profit", precision = 10, scale = 2)
    private BigDecimal deliveryOrderProfit;

    /** How much of the normal charge the order's own margin absorbed. */
    @Column(name = "delivery_subsidy", precision = 10, scale = 2)
    private BigDecimal deliverySubsidy;

    /**
     * Anything the shop should know about how this price was reached - a
     * missing cost price, an item with no weight, an estimated distance.
     *
     * Never shown to a customer. Kept on the order rather than only in a log
     * so that "why was this one odd" is answerable months later, when the log
     * has rotated away.
     */
    @Column(name = "delivery_pricing_notes", length = 1000)
    private String deliveryPricingNotes;

    public Order() {
    }

    public BigDecimal getDeliveryDistanceKm() {
        return deliveryDistanceKm;
    }

    public void setDeliveryDistanceKm(BigDecimal deliveryDistanceKm) {
        this.deliveryDistanceKm = deliveryDistanceKm;
    }

    public BigDecimal getDeliveryWeightKg() {
        return deliveryWeightKg;
    }

    public void setDeliveryWeightKg(BigDecimal deliveryWeightKg) {
        this.deliveryWeightKg = deliveryWeightKg;
    }

    public BigDecimal getDeliveryDistanceCharge() {
        return deliveryDistanceCharge;
    }

    public void setDeliveryDistanceCharge(BigDecimal deliveryDistanceCharge) {
        this.deliveryDistanceCharge = deliveryDistanceCharge;
    }

    public BigDecimal getDeliveryWeightCharge() {
        return deliveryWeightCharge;
    }

    public void setDeliveryWeightCharge(BigDecimal deliveryWeightCharge) {
        this.deliveryWeightCharge = deliveryWeightCharge;
    }

    public BigDecimal getDeliveryNormalCharge() {
        return deliveryNormalCharge;
    }

    public void setDeliveryNormalCharge(BigDecimal deliveryNormalCharge) {
        this.deliveryNormalCharge = deliveryNormalCharge;
    }

    public BigDecimal getDeliveryOrderProfit() {
        return deliveryOrderProfit;
    }

    public void setDeliveryOrderProfit(BigDecimal deliveryOrderProfit) {
        this.deliveryOrderProfit = deliveryOrderProfit;
    }

    public BigDecimal getDeliverySubsidy() {
        return deliverySubsidy;
    }

    public void setDeliverySubsidy(BigDecimal deliverySubsidy) {
        this.deliverySubsidy = deliverySubsidy;
    }

    public String getDeliveryPricingNotes() {
        return deliveryPricingNotes;
    }

    public void setDeliveryPricingNotes(String deliveryPricingNotes) {
        this.deliveryPricingNotes = deliveryPricingNotes;
    }

    public String getQrToken() {
        return qrToken;
    }

    public void setQrToken(String qrToken) {
        this.qrToken = qrToken;
    }

    public LocalDateTime getQrTokenIssuedAt() {
        return qrTokenIssuedAt;
    }

    public void setQrTokenIssuedAt(LocalDateTime qrTokenIssuedAt) {
        this.qrTokenIssuedAt = qrTokenIssuedAt;
    }

    public LocalDateTime getQrTokenUsedAt() {
        return qrTokenUsedAt;
    }

    public void setQrTokenUsedAt(LocalDateTime qrTokenUsedAt) {
        this.qrTokenUsedAt = qrTokenUsedAt;
    }

    public DeliveryPartner getPackedByPartner() {
        return packedByPartner;
    }

    public void setPackedByPartner(DeliveryPartner packedByPartner) {
        this.packedByPartner = packedByPartner;
    }

    public LocalDateTime getPackedAt() {
        return packedAt;
    }

    public void setPackedAt(LocalDateTime packedAt) {
        this.packedAt = packedAt;
    }

    public DeliveryPartner getAssignedWorkerPartner() {
        return assignedWorkerPartner;
    }

    public void setAssignedWorkerPartner(DeliveryPartner assignedWorkerPartner) {
        this.assignedWorkerPartner = assignedWorkerPartner;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public List<OrderItem> getOrderItems() {
        return orderItems;
    }

    public void setOrderItems(List<OrderItem> orderItems) {
        this.orderItems = orderItems;
    }

    public BigDecimal getTotalAmount() {
    return totalAmount;
}

public void setTotalAmount(BigDecimal totalAmount) {
    this.totalAmount = totalAmount;
}

    public String getAppliedCouponCode() {
        return appliedCouponCode;
    }

    public void setAppliedCouponCode(String appliedCouponCode) {
        this.appliedCouponCode = appliedCouponCode;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public Boolean getFreeDeliveryApplied() {
        return freeDeliveryApplied;
    }

    public void setFreeDeliveryApplied(Boolean freeDeliveryApplied) {
        this.freeDeliveryApplied = freeDeliveryApplied;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

  public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public LocalDateTime getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDateTime orderDate) {
        this.orderDate = orderDate;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getInventoryRestored() {
        return inventoryRestored;
    }

    public void setInventoryRestored(Boolean inventoryRestored) {
        this.inventoryRestored = inventoryRestored;
    }
}