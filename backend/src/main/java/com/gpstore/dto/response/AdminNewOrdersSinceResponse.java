package com.gpstore.dto.response;

import java.util.List;

/**
 * Cheap admin poll for the shop-counter soundbox. The first call (no
 * {@code afterId}) returns only the current high-water mark so the app can
 * arm itself without announcing historical orders. Later calls return
 * orders with id greater than {@code afterId}, oldest first, capped so a
 * burst cannot flood the speaker.
 */
public class AdminNewOrdersSinceResponse {

    private final long afterId;
    private final List<AdminNewOrderAlert> orders;

    public AdminNewOrdersSinceResponse(long afterId, List<AdminNewOrderAlert> orders) {
        this.afterId = afterId;
        this.orders = orders;
    }

    public long getAfterId() {
        return afterId;
    }

    public List<AdminNewOrderAlert> getOrders() {
        return orders;
    }

    public static class AdminNewOrderAlert {
        private final long orderId;
        private final String customerName;
        private final String orderAmount;

        public AdminNewOrderAlert(long orderId, String customerName, String orderAmount) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.orderAmount = orderAmount;
        }

        public long getOrderId() {
            return orderId;
        }

        public String getCustomerName() {
            return customerName;
        }

        public String getOrderAmount() {
            return orderAmount;
        }
    }
}
