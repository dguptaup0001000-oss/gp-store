import '../../../core/api/api_client.dart';
import '../domain/notification_models.dart';

class NotificationsRepository {
  NotificationsRepository({required this.apiClient});

  final ApiClient apiClient;

  /// Paginated - every order status change writes a notification, so a
  /// customer's history has no natural upper bound over the years.
  Future<({List<AppNotification> notifications, int totalPages})> getMyNotifications({int page = 0, int size = 20}) async {
    final response = await apiClient.dio.get(
      '/api/notifications/mine',
      queryParameters: {'page': page, 'size': size},
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List;
    return (
      notifications: content.map((e) => AppNotification.fromJson(e as Map<String, dynamic>)).toList(),
      totalPages: data['totalPages'] as int,
    );
  }

  /// Dedicated count query for the unread badge - the notification list
  /// itself is now paginated, so the badge can no longer be derived from a
  /// full in-memory list of every notification.
  Future<int> getUnreadCount() async {
    final response = await apiClient.dio.get('/api/notifications/unread-count');
    return response.data as int;
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
