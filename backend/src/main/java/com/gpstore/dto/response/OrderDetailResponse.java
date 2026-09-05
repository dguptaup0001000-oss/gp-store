package com.gpstore.dto.response;

import com.gpstore.entity.Address;
import com.gpstore.entity.Delivery;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Explicit response shape for a single order - same reasoning as
 * CartResponse: OrderItem.productVariant.product is deliberately hidden from
 * JSON (prevents the recursion bug fixed alongside this), so a raw entity
 * genuinely can't say what was actually ordered. This DTO fills that gap
 * with real product name/brand, plus delivery tracking info when a partner
 * has been assigned - a real "track my order" screen needs both.
 */
public class OrderDetailResponse {

    private final Long orderId;
    private final String orderNumber;
    private final String orderStatus;
    private final String paymentStatus;
    private final LocalDateTime orderDate;
    private final BigDecimal totalAmount;
    private final BigDecimal discountAmount;
    private final BigDecimal deliveryFee;
    private final String appliedCouponCode;
    private final AddressSummary address;
    private final List<OrderItemResponse> items;
    private final DeliveryTrackingInfo delivery;

    /**
     * How a cash-on-delivery order was actually settled at the door.
     *
     * All three are null unless a rider recorded it, which is the ordinary
     * state for an unsettled order and for one settled automatically by the
     * delivery flow. They are on the ORDER response, not left in the payments
     * screen, because the question "did this delivery bring the money back,
     * and in what form" is asked while looking at the order.
     */
    private final BigDecimal codCashAmount;
    private final BigDecimal codUpiAmount;
    private final LocalDateTime codCollectedAt;

    public OrderDetailResponse(Long orderId, String orderNumber, String orderStatus, String paymentStatus,
                                LocalDateTime orderDate, BigDecimal totalAmount, BigDecimal discountAmount,
                                BigDecimal deliveryFee, String appliedCouponCode, AddressSummary address,
                                List<OrderItemResponse> items, DeliveryTrackingInfo delivery) {
        this(orderId, orderNumber, orderStatus, paymentStatus, orderDate, totalAmount, discountAmount,
                deliveryFee, appliedCouponCode, address, items, delivery, null, null, null);
    }

    public OrderDetailResponse(Long orderId, String orderNumber, String orderStatus, String paymentStatus,
                                LocalDateTime orderDate, BigDecimal totalAmount, BigDecimal discountAmount,
                                BigDecimal deliveryFee, String appliedCouponCode, AddressSummary address,
                                List<OrderItemResponse> items, DeliveryTrackingInfo delivery,
                                BigDecimal codCashAmount, BigDecimal codUpiAmount,
                                LocalDateTime codCollectedAt) {
        this.codCashAmount = codCashAmount;
        this.codUpiAmount = codUpiAmount;
        this.codCollectedAt = codCollectedAt;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.orderStatus = orderStatus;
        this.paymentStatus = paymentStatus;
        this.orderDate = orderDate;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.deliveryFee = deliveryFee;
        this.appliedCouponCode = appliedCouponCode;
        this.address = address;
        this.items = items;
        this.delivery = delivery;
    }

    /**
     * Customer-facing by default. Staff must call {@link #forStaff}.
     *
     * The default is the private one on purpose: a new call site that forgets
     * to think about this shows a customer the privacy-safe name, which is
     * the harmless mistake. The other way round leaks.
     */
    public static OrderDetailResponse from(Order order, Delivery deliveryOrNull) {
        return from(order, deliveryOrNull, false, null);
    }

    /** Real product names, for staff who must be able to identify the item. */
    public static OrderDetailResponse forStaff(Order order, Delivery deliveryOrNull) {
        return from(order, deliveryOrNull, true, null);
    }

    /** Staff view that also reports what the payment row actually says. */
    public static OrderDetailResponse forStaff(Order order, Delivery deliveryOrNull, Payment paymentOrNull) {
        return from(order, deliveryOrNull, true, paymentOrNull);
    }

    public static OrderDetailResponse from(Order order, Delivery deliveryOrNull, boolean forStaff) {
        return from(order, deliveryOrNull, forStaff, null);
    }

    /**
     * @param paymentOrNull the order's payment row, when the caller has it.
     *
     * THE PAYMENT IS THE ANSWER, the order's own payment_status column is a
     * stale second copy. It is written once at checkout and never updated,
     * so a COD order whose money a rider handed in an hour ago still reads
     * COD_PENDING from it - which is the screen the shop uses to know which
     * deliveries still owe money. Passing null keeps the old behaviour for
     * an order that genuinely has no payment row (they predate payment
     * creation moving into the order transaction), where the column is the
     * only answer there is.
     */
    public static OrderDetailResponse from(Order order, Delivery deliveryOrNull, boolean forStaff,
                                            Payment paymentOrNull) {
        List<OrderItemResponse> items = order.getOrderItems() == null
                ? List.of()
                : order.getOrderItems().stream().map(i -> OrderItemResponse.from(i, forStaff)).toList();

        String paymentStatus = paymentOrNull != null && paymentOrNull.getPaymentStatus() != null
                ? paymentOrNull.getPaymentStatus().name()
                : order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null;

        return new OrderDetailResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderStatus() != null ? order.getOrderStatus().name() : null,
                paymentStatus,
                order.getOrderDate(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getDeliveryFee(),
                order.getAppliedCouponCode(),
                AddressSummary.from(order.getAddress()),
                items,
                deliveryOrNull != null ? DeliveryTrackingInfo.from(deliveryOrNull) : null,
                paymentOrNull == null ? null : paymentOrNull.getCodCashAmount(),
                paymentOrNull == null ? null : paymentOrNull.getCodUpiAmount(),
                paymentOrNull == null ? null : paymentOrNull.getCodCollectedAt()
        );
    }

    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public String getOrderStatus() { return orderStatus; }
    public String getPaymentStatus() { return paymentStatus; }
    public LocalDateTime getOrderDate() { return orderDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getDeliveryFee() { return deliveryFee; }
    public String getAppliedCouponCode() { return appliedCouponCode; }
    public AddressSummary getAddress() { return address; }
    public List<OrderItemResponse> getItems() { return items; }
    public DeliveryTrackingInfo getDelivery() { return delivery; }

    /** Null unless a rider recorded how a COD was settled. */
    public BigDecimal getCodCashAmount() { return codCashAmount; }
    public BigDecimal getCodUpiAmount() { return codUpiAmount; }
    public LocalDateTime getCodCollectedAt() { return codCollectedAt; }

    public static class OrderItemResponse {
        /**
         * The ORDER LINE's id, which returns need and nothing else did.
         *
         * variantId is not enough: two lines on one order can carry the same
         * variant at different prices - a coupon applied to one, a price
         * change between orders - so "return the variant" cannot say which
         * price to refund. A return names the line.
         */
        private final Long orderItemId;
        private final Long variantId;
        private final String productName;
        private final String productBrand;
        private final Double variantQuantity;
        private final String unit;
        private final String imageUrl;
        private final Integer quantity;
        private final BigDecimal price;
        private final BigDecimal totalPrice;
        // Whether this variant can still actually be re-added to cart today -
        // NOT the same as whether it was available when originally ordered.
        // "Buy Again" needs to know this to skip items that have since been
        // discontinued or gone permanently out of stock.
        private final Boolean currentlyAvailable;

        private OrderItemResponse(Long orderItemId, Long variantId, String productName, String productBrand, Double variantQuantity,
                                   String unit, String imageUrl, Integer quantity, BigDecimal price,
                                   BigDecimal totalPrice, Boolean currentlyAvailable) {
            this.orderItemId = orderItemId;
            this.variantId = variantId;
            this.productName = productName;
            this.productBrand = productBrand;
            this.variantQuantity = variantQuantity;
            this.unit = unit;
            this.imageUrl = imageUrl;
            this.quantity = quantity;
            this.price = price;
            this.totalPrice = totalPrice;
            this.currentlyAvailable = currentlyAvailable;
        }

        /**
         * PRIVACY LIVES HERE, not in the app.
         *
         * A private product's real name is replaced before the response is
         * built, so a customer-facing payload never carries it at all - there
         * is nothing for a client to accidentally render, log, or cache. Staff
         * get the real name because fulfilling, refunding and auditing an
         * order all require knowing what was actually sold.
         *
         * Read live from the product rather than copied onto the order line at
         * purchase time: marking a product private has to take effect for
         * orders already placed, which is the whole point of marking it.
         */
        static OrderItemResponse from(OrderItem item, boolean forStaff) {
            var variant = item.getProductVariant();
            var product = variant != null ? variant.getProduct() : null;

            String displayName = product == null ? null
                    : (forStaff ? product.getName() : product.customerFacingName());

            return new OrderItemResponse(
                    item.getId(),
                    variant != null ? variant.getId() : null,
                    displayName,
                    product != null ? product.getBrand() : null,
                    variant != null ? variant.getQuantity() : null,
                    variant != null ? variant.getUnit() : null,
                    variant != null && variant.getImageUrl() != null
                            ? com.gpstore.upload.CatalogImageDelivery.forClient(variant.getImageUrl())
                            : null,
                    item.getQuantity(),
                    item.getPrice(),
                    item.getTotalPrice(),
                    variant != null ? variant.getAvailable() : null
            );
        }

        public Long getOrderItemId() { return orderItemId; }
        public Long getVariantId() { return variantId; }
        public String getProductName() { return productName; }
        public String getProductBrand() { return productBrand; }
        public Double getVariantQuantity() { return variantQuantity; }
        public String getUnit() { return unit; }
        public String getImageUrl() { return imageUrl; }
        public Integer getQuantity() { return quantity; }
        public BigDecimal getPrice() { return price; }
        public BigDecimal getTotalPrice() { return totalPrice; }
        public Boolean getCurrentlyAvailable() { return currentlyAvailable; }
    }

    public static class AddressSummary {
        private final String fullName;
        private final String fullAddress;
        private final String mobileNumber;

        private AddressSummary(String fullName, String fullAddress, String mobileNumber) {
            this.fullName = fullName;
            this.fullAddress = fullAddress;
            this.mobileNumber = mobileNumber;
        }

        static AddressSummary from(Address address) {
            if (address == null) return null;

            String landmarkPart = address.getLandmark() != null ? ", " + address.getLandmark() : "";
            String fullAddress = address.getHouseNo() + ", " + address.getArea() + landmarkPart
                    + ", " + address.getCity() + ", " + address.getState() + " - " + address.getPincode();

            return new AddressSummary(address.getFullName(), fullAddress, address.getMobileNumber());
        }

        public String getFullName() { return fullName; }
        public String getFullAddress() { return fullAddress; }
        public String getMobileNumber() { return mobileNumber; }
    }

    public static class DeliveryTrackingInfo {
        private final String deliveryStatus;
        private final LocalDateTime estimatedDeliveryTime;
        private final LocalDateTime deliveredAt;
        private final String deliveryPersonName;
        private final String deliveryPersonPhone;
        private final Boolean guaranteeBreached;

        private DeliveryTrackingInfo(String deliveryStatus, LocalDateTime estimatedDeliveryTime,
                                      LocalDateTime deliveredAt, String deliveryPersonName,
                                      String deliveryPersonPhone, Boolean guaranteeBreached) {
            this.deliveryStatus = deliveryStatus;
            this.estimatedDeliveryTime = estimatedDeliveryTime;
            this.deliveredAt = deliveredAt;
            this.deliveryPersonName = deliveryPersonName;
            this.deliveryPersonPhone = deliveryPersonPhone;
            this.guaranteeBreached = guaranteeBreached;
        }

        static DeliveryTrackingInfo from(Delivery delivery) {
            return new DeliveryTrackingInfo(
                    delivery.getDeliveryStatus(),
                    delivery.getEstimatedDeliveryTime(),
                    delivery.getDeliveredAt(),
                    delivery.getDeliveryPersonName(),
                    delivery.getDeliveryPersonPhone(),
                    delivery.getGuaranteeBreached()
            );
        }

        public String getDeliveryStatus() { return deliveryStatus; }
        public LocalDateTime getEstimatedDeliveryTime() { return estimatedDeliveryTime; }
        public LocalDateTime getDeliveredAt() { return deliveredAt; }
        public String getDeliveryPersonName() { return deliveryPersonName; }
        public String getDeliveryPersonPhone() { return deliveryPersonPhone; }
        public Boolean getGuaranteeBreached() { return guaranteeBreached; }
    }
}
