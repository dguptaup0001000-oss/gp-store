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

  Future<PlaceOrderResult> placeOrder({
    required int addressId,
    required String paymentMethod,
    String? couponCode,
  }) async {
    final response = await apiClient.dio.post(
      '/api/orders/place',
      data: {
        'addressId': addressId,
        'paymentMethod': paymentMethod,
        if (couponCode != null && couponCode.isNotEmpty) 'couponCode': couponCode,
      },
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
