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
