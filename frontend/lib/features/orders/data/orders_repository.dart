import '../../../core/api/api_client.dart';
import '../domain/invoice_model.dart';
import '../domain/live_tracking_model.dart';
import '../domain/order_models.dart';

class OrdersRepository {
  OrdersRepository({required this.apiClient});

  final ApiClient apiClient;

  /// Backend returns a Spring Data Page (a repeat customer's order history
  /// has no natural upper bound over the years) - so the response is a JSON
  /// object with a "content" array, not a plain list.
  Future<({List<OrderSummary> orders, int totalPages})> getMyOrders({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/orders/my-orders',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      orders: content.map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  Future<OrderDetail> getOrderDetail(int orderId) async {
    final response = await apiClient.dio.get('/api/orders/$orderId');
    return OrderDetail.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> cancelOrder(int orderId) async {
    await apiClient.dio.put('/api/orders/$orderId/cancel');
  }

  /// Ownership is enforced server-side - fails with a real "not found" if
  /// this isn't actually the caller's own order.
  Future<Invoice> getInvoiceForOrder(int orderId) async {
    final response = await apiClient.dio.get('/api/invoices/my-order/$orderId');
    return Invoice.fromJson(response.data as Map<String, dynamic>);
  }

  /// Live GPS position of the assigned delivery partner - a separate,
  /// lightweight endpoint from getOrderDetail() above, meant to be polled
  /// frequently while the order is OUT_FOR_DELIVERY without re-fetching the
  /// whole order every time. Ownership enforced server-side, same as
  /// getOrderDetail().
  Future<LiveDeliveryLocation> getLiveTracking(int orderId) async {
    final response = await apiClient.dio.get('/api/deliveries/my-order/$orderId/tracking');
    return LiveDeliveryLocation.fromJson(response.data as Map<String, dynamic>);
  }
}
