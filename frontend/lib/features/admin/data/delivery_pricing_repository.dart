import '../../../core/api/api_client.dart';
import '../domain/delivery_pricing_models.dart';

/// Admin delivery-pricing HTTP client. Display and persist only — no quote
/// arithmetic lives here.
class DeliveryPricingRepository {
  DeliveryPricingRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<DeliveryPricingSettings> getSettings() async {
    final response = await apiClient.dio.get('/api/admin/delivery-pricing/settings');
    return DeliveryPricingSettings.fromJson(response.data as Map<String, dynamic>);
  }

  Future<DeliveryPricingSettings> saveSettings(DeliveryPricingSettings settings) async {
    final response = await apiClient.dio.put(
      '/api/admin/delivery-pricing/settings',
      data: settings.toJson(),
    );
    return DeliveryPricingSettings.fromJson(response.data as Map<String, dynamic>);
  }

  Future<DeliveryOrderBreakdown> getOrderBreakdown(int orderId) async {
    final response = await apiClient.dio.get('/api/admin/delivery-pricing/orders/$orderId');
    return DeliveryOrderBreakdown.fromJson(response.data as Map<String, dynamic>);
  }
}
