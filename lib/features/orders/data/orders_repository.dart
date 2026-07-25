import '../../../core/api/api_client.dart';
import '../domain/invoice_model.dart';
import '../domain/order_models.dart';

class OrdersRepository {
  OrdersRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<OrderSummary>> getMyOrders() async {
    final response = await apiClient.dio.get('/api/orders/my-orders');
    return (response.data as List).map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList();
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
}
