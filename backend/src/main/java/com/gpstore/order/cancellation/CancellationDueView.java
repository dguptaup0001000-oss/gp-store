package com.gpstore.order.cancellation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One cancellation debt, as the customer or the shop sees it.
 *
 * <p>A DTO rather than the entity, for the ordinary reason and one specific
 * one: the entity is shop-owned and carries its shop id, and the customer's
 * own list legitimately spans shops. Handing the entity out would either leak
 * the internal id or need the field stripped at every call site.
 */
public record CancellationDueView(
        Long id,
        Long orderId,
        BigDecimal amount,
        DueStatus status,
        String reason,
        LocalDateTime createdAt,
        Long settledOrderId,
        LocalDateTime settledAt) {

    public static CancellationDueView from(CustomerCancellationDue due) {
        return new CancellationDueView(
                due.getId(), due.getOrderId(), due.getAmount(), due.getStatus(),
                due.getReason(), due.getCreatedAt(), due.getSettledOrderId(), due.getSettledAt());
    }
}
