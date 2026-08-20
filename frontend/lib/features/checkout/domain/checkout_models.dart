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
  }) = _PlaceOrderResult;

  factory PlaceOrderResult.fromJson(Map<String, dynamic> json) => _$PlaceOrderResultFromJson(json);
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
