import '../../../core/api/api_client.dart';
import '../domain/notification_models.dart';

class NotificationsRepository {
  NotificationsRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<AppNotification>> getMyNotifications() async {
    final response = await apiClient.dio.get('/api/notifications/mine');
    return (response.data as List).map((e) => AppNotification.fromJson(e as Map<String, dynamic>)).toList();
  }
}
