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