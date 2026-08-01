import '../../../core/api/api_client.dart';
import '../domain/notification_models.dart';

class NotificationsRepository {
  NotificationsRepository({required this.apiClient});

  final ApiClient apiClient;

  Future<List<AppNotification>> getMyNotifications() async {
    final response = await apiClient.dio.get('/api/notifications/mine');
    return (response.data as List).map((e) => AppNotification.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Ownership is enforced server-side - can only mark the caller's own notification.
  Future<void> markAsRead(int notificationId) async {
    await apiClient.dio.put('/api/notifications/$notificationId/read');
  }

  Future<void> markAllAsRead() async {
    await apiClient.dio.put('/api/notifications/read-all');
  }

  /// Ownership is enforced server-side - can only delete the caller's own notification.
  Future<void> deleteNotification(int notificationId) async {
    await apiClient.dio.delete('/api/notifications/$notificationId');
  }
}
