import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/config/app_environment.dart';
import 'package:gpstore/features/notifications/data/notifications_repository.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  test('uses the exact version-relative routes exposed by Spring', () async {
    final adapter = FakeHttpClientAdapter();
    final calls = <String>[];

    adapter.on('GET', '/api/notifications/mine', (options) {
      expect(options.uri.toString(),
          'https://api.gpstore.co.in/v1/api/notifications/mine?page=0&size=20');
      calls.add('${options.method} ${options.path} '
          '${options.queryParameters['page']}:${options.queryParameters['size']}');
      return const FakeResponse({
        'content': [
          {
            'id': 7,
            'title': 'Order packed',
            'message': 'Your order is packed.',
            'sentAt': '2026-09-22T10:00:00',
            'isRead': false,
          }
        ],
        'totalPages': 1,
      });
    });
    adapter.on('GET', '/api/notifications/unread-count', (options) {
      expect(options.uri.toString(),
          'https://api.gpstore.co.in/v1/api/notifications/unread-count');
      calls.add('${options.method} ${options.path}');
      return const FakeResponse(1);
    });
    adapter.on('PUT', '/api/notifications/7/read', (options) {
      expect(options.uri.toString(),
          'https://api.gpstore.co.in/v1/api/notifications/7/read');
      calls.add('${options.method} ${options.path}');
      return const FakeResponse('Marked as read');
    });
    adapter.on('PUT', '/api/notifications/read-all', (options) {
      expect(options.uri.toString(),
          'https://api.gpstore.co.in/v1/api/notifications/read-all');
      calls.add('${options.method} ${options.path}');
      return const FakeResponse('0 notifications marked as read');
    });

    adapter.on('DELETE', '/api/notifications/7', (options) {
      expect(options.uri.toString(),
          'https://api.gpstore.co.in/v1/api/notifications/7');
      calls.add('${options.method} ${options.path}');
      return const FakeResponse('Notification removed');
    });

    final repository = NotificationsRepository(apiClient: buildTestApiClient(
      adapter,
      environment: AppEnvironment.production,
    ));
    final page = await repository.getMyNotifications(page: 0, size: 20);
    expect(page.notifications.single.title, 'Order packed');
    expect(await repository.getUnreadCount(), 1);
    await repository.markAsRead(7);
    await repository.markAllAsRead();
    await repository.deleteNotification(7);

    expect(calls, [
      'GET /api/notifications/mine 0:20',
      'GET /api/notifications/unread-count',
      'PUT /api/notifications/7/read',
      'PUT /api/notifications/read-all',
      'DELETE /api/notifications/7',
    ]);
  });

  test('a successful empty page parses as an empty notification state',
      () async {
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/notifications/mine', (options) {
      return const FakeResponse({
        'content': <Object>[],
        'totalPages': 0,
        'totalElements': 0,
      });
    });

    final page = await NotificationsRepository(
      apiClient: buildTestApiClient(
        adapter,
        environment: AppEnvironment.production,
      ),
    ).getMyNotifications();

    expect(page.notifications, isEmpty);
    expect(page.totalPages, 0);
  });
}
