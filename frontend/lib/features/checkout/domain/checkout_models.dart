import 'package:freezed_annotation/freezed_annotation.dart';

part 'checkout_models.freezed.dart';
part 'checkout_models.g.dart';

@freezed
class CheckoutPreview with _$CheckoutPreview {
  const factory CheckoutPreview({
    required double subtotal,
    required double discountAmount,
    required double deliveryFee,
    required double estimatedTotal,
    required bool freeDeliveryApplied,
    required bool deliverable,
    int? estimatedDeliveryMinutes,
    String? couponError,
  }) = _CheckoutPreview;

  factory CheckoutPreview.fromJson(Map<String, dynamic> json) => _$CheckoutPreviewFromJson(json);
}

@freezed
class PlaceOrderResult with _$PlaceOrderResult {
  const factory PlaceOrderResult({
    required bool success,
    int? orderId,
    String? orderNumber,
    String? message,
    /// Present when the backend created the payment as part of placing the
    /// order, which is now the normal path. When it is set, the client can
    /// skip the separate POST /api/payments call - that second request is
    /// what made checkout two sequential round trips.
    String? paymentStatus,

    /// UPI deep link, returned alongside the order when paying by UPI.
    /// Generated locally server-side (no gateway call), which is why it can
    /// come back with the order rather than needing its own request.
    String? upiPaymentLink,

    /// THE CHECKOUT, as opposed to the first order in it.
    ///
    /// orderId and orderNumber above still describe ONE order, because that
    /// is what every APK already on a customer's phone reads. These are
    /// beside them: under one shop a checkout is one order and shopOrders has
    /// a single entry, so the confirmation screen renders exactly as it
    /// always did. Under a marketplace they are what stops a customer who
    /// paid two kiranas being shown one order number.
    int? orderGroupId,
    String? orderGroupNumber,
    @Default([]) List<PlacedShopOrder> shopOrders,
  }) = _PlaceOrderResult;

  factory PlaceOrderResult.fromJson(Map<String, dynamic> json) => _$PlaceOrderResultFromJson(json);
}

/// One shop's order inside a checkout, as PlaceOrderResponse.ShopOrderSummary
/// sends it.
@freezed
class PlacedShopOrder with _$PlacedShopOrder {
  const factory PlacedShopOrder({
    required int orderId,
    String? orderNumber,
    int? shopId,
    double? totalAmount,
    double? deliveryFee,
    String? paymentStatus,
    String? upiPaymentLink,
  }) = _PlacedShopOrder;

  factory PlacedShopOrder.fromJson(Map<String, dynamic> json) =>
      _$PlacedShopOrderFromJson(json);
}

/// Mirrors backend's PaymentInitiationResponse exactly - it nests a full
/// Payment object, not a flat paymentId.
@freezed
class PaymentDetails with _$PaymentDetails {
  const factory PaymentDetails({
    required int id,
    required String paymentMethod,
    required String paymentStatus,
    required double amount,
  }) = _PaymentDetails;

  factory PaymentDetails.fromJson(Map<String, dynamic> json) => _$PaymentDetailsFromJson(json);
}

@freezed
class PaymentInitiationResult with _$PaymentInitiationResult {
  const factory PaymentInitiationResult({
    required PaymentDetails payment,
    String? upiPaymentLink,
  }) = _PaymentInitiationResult;

  factory PaymentInitiationResult.fromJson(Map<String, dynamic> json) => _$PaymentInitiationResultFromJson(json);
}
