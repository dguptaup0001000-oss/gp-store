import '../api/api_client.dart';
import 'store_status.dart';

/// Reads the shop's opening state from the server.
///
/// PUBLIC ENDPOINT: this works signed out, because a customer browsing at 3am
/// before they log in is exactly who needs the answer.
class StoreStatusRepository {
  StoreStatusRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<StoreStatus> fetch() async {
    final response = await apiClient.dio.get('/api/store/status');
    return StoreStatus.fromJson(response.data as Map<String, dynamic>);
  }
}
