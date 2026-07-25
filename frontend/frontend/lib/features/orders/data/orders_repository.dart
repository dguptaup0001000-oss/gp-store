import '../../../core/api/api_client.dart';
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
}
