package com.gpstore.returns;

import com.gpstore.entity.OrderReturn;
import com.gpstore.entity.OrderReturnItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A return as either side needs to see it.
 *
 * The staff who decided is named by id only. A shopkeeper looking at their
 * own queue does not need another staff member's contact details, and a
 * customer must never receive them.
 */
public record ReturnResponse(
        Long id,
        Long orderId,
        String orderNumber,
        String status,
        String reason,
        String decisionNote,
        BigDecimal refundAmount,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        Long decidedById,
        List<Line> items
) {

    public record Line(
            Long orderItemId,
            String productName,
            String pack,
            Integer quantity,
            BigDecimal unitPrice,
            String imageUrl
    ) {}

    public static ReturnResponse from(OrderReturn request) {
        List<Line> lines = request.getItems().stream()
                .map(ReturnResponse::lineOf)
                .toList();

        return new ReturnResponse(
                request.getId(),
                request.getOrder() == null ? null : request.getOrder().getId(),
                request.getOrder() == null ? null : request.getOrder().getOrderNumber(),
                request.getStatus() == null ? null : request.getStatus().name(),
                request.getReason(),
                request.getDecisionNote(),
                request.getRefundAmount(),
                request.getRequestedAt(),
                request.getDecidedAt(),
                request.getDecidedBy() == null ? null : request.getDecidedBy().getId(),
                lines);
    }

    private static Line lineOf(OrderReturnItem item) {
        var orderItem = item.getOrderItem();
        var variant = orderItem == null ? null : orderItem.getProductVariant();
        var product = variant == null ? null : variant.getProduct();

        String pack = null;
        if (variant != null && variant.getQuantity() != null && variant.getUnit() != null) {
            double q = variant.getQuantity();
            String number = q == Math.rint(q) ? String.valueOf((long) q) : String.valueOf(q);
            pack = number + " " + variant.getUnit();
        }

        // Signed on the way out: a stored delivery URL expires within the hour.
        String image = null;
        if (variant != null && variant.getImageUrl() != null && !variant.getImageUrl().isBlank()) {
            image = com.gpstore.upload.CatalogImageDelivery.forClient(variant.getImageUrl());
        }

        return new Line(
                orderItem == null ? null : orderItem.getId(),
                product == null ? "Item" : product.getName(),
                pack,
                item.getQuantity(),
                item.getUnitPrice(),
                image);
    }
}
