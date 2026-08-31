import 'package:freezed_annotation/freezed_annotation.dart';

import 'payment_status.dart';

part 'order_models.freezed.dart';
part 'order_models.g.dart';

/// Mirrors backend's OrderResponse (the /my-orders list shape).
@freezed
class OrderSummary with _$OrderSummary {
  const factory OrderSummary({
    required int orderId,
    required String orderNumber,
    required double totalAmount,
    required String orderStatus,
    required String paymentStatus,
    required String orderDate,
    // SAME_DAY / NEXT_MORNING, decided by the server when the order was
    // placed. NULL for every order placed before the shop had delivery
    // windows - shown as nothing rather than guessed at, because a label
    // invented for a delivery that already happened is simply untrue.
    String? deliveryType,
    // The day it was scheduled for, as YYYY-MM-DD. Null for the same reason.
    String? scheduledDeliveryDate,
    // Only populated on the admin "all orders" list - null for a
    // customer's own list, where it would be redundant.
    String? customerName,
  }) = _OrderSummary;

  factory OrderSummary.fromJson(Map<String, dynamic> json) =>
      _$OrderSummaryFromJson(json);
}

@freezed
class OrderItemDetail with _$OrderItemDetail {
  const factory OrderItemDetail({
    int? variantId,
    String? productName,
    String? productBrand,
    double? variantQuantity,
    String? unit,
    String? imageUrl,
    required int quantity,
    required double price,
    required double totalPrice,
    // Whether this item can be re-added to cart TODAY - not whether it was
    // available when originally ordered. Needed for "Buy Again" to skip
    // discontinued items rather than fail the whole action.
    bool? currentlyAvailable,
  }) = _OrderItemDetail;

  factory OrderItemDetail.fromJson(Map<String, dynamic> json) =>
      _$OrderItemDetailFromJson(json);
}

@freezed
class OrderAddressSummary with _$OrderAddressSummary {
  const factory OrderAddressSummary({
    required String fullName,
    required String fullAddress,
    required String mobileNumber,
  }) = _OrderAddressSummary;

  factory OrderAddressSummary.fromJson(Map<String, dynamic> json) =>
      _$OrderAddressSummaryFromJson(json);
}

@freezed
class DeliveryTrackingInfo with _$DeliveryTrackingInfo {
  const factory DeliveryTrackingInfo({
    String? deliveryStatus,
    String? estimatedDeliveryTime,
    String? deliveredAt,
    String? deliveryPersonName,
    String? deliveryPersonPhone,
    bool? guaranteeBreached,
  }) = _DeliveryTrackingInfo;

  factory DeliveryTrackingInfo.fromJson(Map<String, dynamic> json) =>
      _$DeliveryTrackingInfoFromJson(json);
}

/// Mirrors backend's OrderDetailResponse exactly.
@freezed
class OrderDetail with _$OrderDetail {
  const factory OrderDetail({
    required int orderId,
    required String orderNumber,
    required String orderStatus,
    required String paymentStatus,
    required String orderDate,
    required double totalAmount,
    double? discountAmount,
    double? deliveryFee,
    String? appliedCouponCode,
    OrderAddressSummary? address,
    @Default([]) List<OrderItemDetail> items,
    DeliveryTrackingInfo? delivery,
  }) = _OrderDetail;

  const OrderDetail._();

  factory OrderDetail.fromJson(Map<String, dynamic> json) =>
      _$OrderDetailFromJson(json);

  /// Only these statuses can still be cancelled by the customer - mirrors
  /// the backend's own valid-transition rules (delivered/cancelled orders
  /// reject cancellation server-side regardless, this is just so the UI
  /// doesn't show a button that would obviously fail).
  bool get isCancellable =>
      orderStatus != 'DELIVERED' && orderStatus != 'CANCELLED';
}

extension OrderSummaryPayment on OrderSummary {
  bool get needsOnlinePayment => PaymentStatusInfo.needsOnlineRetry(
        paymentStatus: paymentStatus,
        orderStatus: orderStatus,
      );
}

extension OrderDetailPayment on OrderDetail {
  bool get needsOnlinePayment => PaymentStatusInfo.needsOnlineRetry(
        paymentStatus: paymentStatus,
        orderStatus: orderStatus,
      );
}
