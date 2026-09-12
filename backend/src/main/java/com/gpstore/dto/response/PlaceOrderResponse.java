package com.gpstore.dto.response;

public class PlaceOrderResponse {

    private boolean success;
    private Long orderId;
    private String orderNumber;
    private String message;

    /**
     * Set when the order's payment was created as part of placing it, which
     * is now the normal path. A client seeing this can skip the separate
     * POST /api/payments call entirely - that call is what made checkout two
     * sequential round trips.
     *
     * Null only for a replayed idempotent response rebuilt from a stored
     * record, where the payment already exists but is not re-read.
     */
    private String paymentStatus;

    /**
     * The UPI deep link, when the order was placed with UPI. Pure local
     * string building server-side (no gateway call), which is why it can be
     * returned here rather than requiring a second request.
     */
    private String upiPaymentLink;

    /**
     * The checkout this order belonged to, and every shop's order in it.
     *
     * ADDED BESIDE THE OLD FIELDS, never in place of them. Every APK already
     * on a customer's phone reads orderId/orderNumber/paymentStatus and knows
     * nothing about groups, so those keep describing one order - the first
     * shop's - and a single-shop checkout answers exactly what it always did.
     */
    private Long orderGroupId;

    private String orderGroupNumber;

    private java.util.List<ShopOrderSummary> shopOrders = java.util.List.of();

    /** One shop's part of a checkout: what they will pack, and what it costs. */
    public record ShopOrderSummary(Long orderId, String orderNumber, Long shopId,
                                   java.math.BigDecimal totalAmount,
                                   java.math.BigDecimal deliveryFee,
                                   String paymentStatus, String upiPaymentLink) {}

    public Long getOrderGroupId() { return orderGroupId; }

    public void setOrderGroupId(Long orderGroupId) { this.orderGroupId = orderGroupId; }

    public String getOrderGroupNumber() { return orderGroupNumber; }

    public void setOrderGroupNumber(String orderGroupNumber) { this.orderGroupNumber = orderGroupNumber; }

    public java.util.List<ShopOrderSummary> getShopOrders() { return shopOrders; }

    public void setShopOrders(java.util.List<ShopOrderSummary> shopOrders) {
        this.shopOrders = shopOrders == null ? java.util.List.of() : java.util.List.copyOf(shopOrders);
    }

    public PlaceOrderResponse() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getUpiPaymentLink() {
        return upiPaymentLink;
    }

    public void setUpiPaymentLink(String upiPaymentLink) {
        this.upiPaymentLink = upiPaymentLink;
    }
}