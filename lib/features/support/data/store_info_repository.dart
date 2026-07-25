import '../../../core/api/api_client.dart';
import '../domain/store_info_model.dart';

class StoreInfoRepository {
  StoreInfoRepository({required this.apiClient});

  final ApiClient apiClient;

  /// Public endpoint - no auth needed, since a locked-out customer might
  /// need support contact info before they can even log in.
  Future<StoreInfo> getStoreInfo() async {
    final response = await apiClient.dio.get('/api/store-info');
    return StoreInfo.fromJson(response.data as Map<String, dynamic>);
  }
}
