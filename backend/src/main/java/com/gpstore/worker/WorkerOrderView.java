package com.gpstore.worker;

import com.gpstore.entity.Address;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Payment;
import com.gpstore.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One order, as much of it as a worker needs and no more.
 *
 * THE OMISSIONS ARE THE DESIGN. This deliberately does not carry the
 * customer's account, their order history, product images, GST breakdowns,
 * discounts, the coupon used, or the payment instrument. A worker packs a
 * carton and carries it to a door; none of that helps them do either, and all
 * of it is a customer's business travelling to a phone in a shop.
 *
 * WHAT IT DOES CARRY, and why each earns its place:
 *
 *   items          - a packing list. This is the one thing a worker cannot do
 *                    their job without, and the reason this view exists at all
 *                    rather than reusing MyDeliveryResponse (which has no
 *                    items, correctly, because a delivery LIST does not need
 *                    them).
 *   amountToCollect - cash, and only when there is cash. See below.
 *   address/phone  - to get there and to call when the lane is unmarked.
 *   status         - so the screen can show where the order actually is
 *                    rather than what the app last remembered.
 *
 * AMOUNT TO COLLECT IS NOT THE ORDER TOTAL. A prepaid order's total is a
 * number a worker has no use for, and showing it at a doorstep is how a
 * customer gets asked to pay twice. This is zero unless the payment is a COD
 * payment that has not been settled - in which case it is exactly the sum to
 * take, and saying so is the whole point.
 */
public record WorkerOrderView(
        Long orderId,
        String orderNumber,
        String orderStatus,
        String deliveryStatus,
        Long deliveryId,
        List<String> allowedNext,

        String customerName,
        String customerPhone,
        String deliveryAddress,
        Double latitude,
        Double longitude,

        int totalItems,
        List<Line> items,

        /** Cash to take at the door. Zero for anything already paid. */
        BigDecimal amountToCollect,
        boolean cashOnDelivery,

        LocalDateTime packedAt,
        String packedBy) {

    /** One line on the packing list. */
    public record Line(String name, String pack, int quantity) {
    }

    public static WorkerOrderView of(Order order,
                                     com.gpstore.entity.Delivery delivery,
                                     Payment payment,
                                     List<String> allowedNext) {
        Address address = order.getAddress();

        List<Line> lines = new ArrayList<>();
        int count = 0;
        if (order.getOrderItems() != null) {
            for (OrderItem item : order.getOrderItems()) {
                if (item == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                    continue;
                }
                ProductVariant variant = item.getProductVariant();
                String name = "Item";
                String pack = null;
                if (variant != null) {
                    if (variant.getProduct() != null && variant.getProduct().getName() != null) {
                        name = variant.getProduct().getName();
                    } else if (variant.getSku() != null) {
                        name = variant.getSku();
                    }
                    // "500 g", "1 kg" - what is printed on the packet, which
                    // is how a worker tells two shelf-neighbours apart.
                    if (variant.getQuantity() != null && variant.getUnit() != null) {
                        pack = trimNumber(variant.getQuantity()) + " " + variant.getUnit();
                    }
                }
                lines.add(new Line(name, pack, item.getQuantity()));
                count += item.getQuantity();
            }
        }

        boolean cod = payment != null
                && com.gpstore.enums.PaymentMethod.COD.name().equals(payment.getPaymentMethod());
        boolean settled = payment != null
                && com.gpstore.enums.PaymentStatus.SUCCESS.name().equals(payment.getPaymentStatus());

        BigDecimal toCollect = cod && !settled && order.getTotalAmount() != null
                ? order.getTotalAmount()
                : BigDecimal.ZERO;

        String fullAddress = address == null ? null
                : address.getHouseNo() + ", " + address.getArea()
                        + (address.getLandmark() != null ? ", " + address.getLandmark() : "")
                        + ", " + address.getCity() + " - " + address.getPincode();

        return new WorkerOrderView(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderStatus() == null ? null : order.getOrderStatus().name(),
                delivery == null ? null : delivery.getDeliveryStatus(),
                delivery == null ? null : delivery.getId(),
                allowedNext == null ? List.of() : List.copyOf(allowedNext),

                address == null ? null : address.getFullName(),
                address == null ? null : address.getMobileNumber(),
                fullAddress,
                address == null ? null : address.getLatitude(),
                address == null ? null : address.getLongitude(),

                count,
                List.copyOf(lines),

                toCollect,
                cod && !settled,

                order.getPackedAt(),
                order.getPackedByPartner() == null ? null : order.getPackedByPartner().getName());
    }

    /** 500.0 reads as 500; 0.5 stays 0.5. Nobody writes "500.0 g" on a packet. */
    private static String trimNumber(Double value) {
        if (value == null) {
            return "";
        }
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) (double) value);
        }
        return String.valueOf(value);
    }
}
