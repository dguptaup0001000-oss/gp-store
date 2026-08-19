import 'package:dio/dio.dart';

import '../../../core/api/api_client.dart';
import '../domain/checkout_models.dart';

class CheckoutRepository {
  CheckoutRepository({required this.apiClient});

  final ApiClient apiClient;

  /// Read-only - safe to call repeatedly as the customer changes address or
  /// types a coupon code, without any side effects on the backend.
  Future<CheckoutPreview> getPreview({required int addressId, String? couponCode}) async {
    final response = await apiClient.dio.get(
      '/api/orders/checkout-preview',
      queryParameters: {
        'addressId': addressId,
        if (couponCode != null && couponCode.isNotEmpty) 'couponCode': couponCode,
      },
    );
    return CheckoutPreview.fromJson(response.data as Map<String, dynamic>);
  }

  /// [idempotencyKey] must be generated ONCE per logical checkout and re-sent
  /// unchanged on every retry of that same checkout - see the caller in
  /// checkout_screen.dart for how it is held and when it is discarded.
  ///
  /// The backend requires this header (see OrderService.placeOrder): without
  /// it, a request that timed out but actually succeeded server-side gets
  /// retried into a SECOND real order, and the customer is charged twice for
  /// one purchase with nothing detecting it. Dio itself can also re-send a
  /// request on a connection reset, which is not visible from here at all -
  /// the key is what makes that safe rather than the UI trying to guarantee
  /// exactly one send.
  Future<PlaceOrderResult> placeOrder({
    required int addressId,
    required String paymentMethod,
    required String idempotencyKey,
    String? couponCode,
  }) async {
    final response = await apiClient.dio.post(
      '/api/orders/place',
      data: {
        'addressId': addressId,
        'paymentMethod': paymentMethod,
        if (couponCode != null && couponCode.isNotEmpty) 'couponCode': couponCode,
      },
      options: Options(headers: {'Idempotency-Key': idempotencyKey}),
    );
    return PlaceOrderResult.fromJson(response.data as Map<String, dynamic>);
  }

  /// Real second step after placeOrder - the order doesn't automatically
  /// create a Payment record; this does, and for UPI returns the real
  /// deep link to open.
  Future<PaymentInitiationResult> initiatePayment({
    required int orderId,
    required String paymentMethod,
  }) async {
    final response = await apiClient.dio.post(
      '/api/payments',
      data: {'orderId': orderId, 'paymentMethod': paymentMethod},
    );
    return PaymentInitiationResult.fromJson(response.data as Map<String, dynamic>);
  }
}
