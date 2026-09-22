import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/notifications/data/notifications_repository.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  test('uses the exact version-relative routes exposed by Spring', () async {
    final adapter = FakeHttpClientAdapter();
    final calls = <String>[];

    adapter.on('GET', '/api/notifications/mine', (options) {
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
      calls.add('${options.method} ${options.path}');
      return const FakeResponse(1);
    });
    adapter.on('PUT', '/api/notifications/7/read', (options) {
      calls.add('${options.method} ${options.path}');
      return const FakeResponse('Marked as read');
    });
    adapter.on('PUT', '/api/notifications/read-all', (options) {
      calls.add('${options.method} ${options.path}');
      return const FakeResponse('0 notifications marked as read');
    });

    final repository = NotificationsRepository(
        apiClient: buildTestApiClient(adapter));
    final page = await repository.getMyNotifications(page: 0, size: 20);
    expect(page.notifications.single.title, 'Order packed');
    expect(await repository.getUnreadCount(), 1);
    await repository.markAsRead(7);
    await repository.markAllAsRead();

    expect(calls, [
      'GET /api/notifications/mine 0:20',
      'GET /api/notifications/unread-count',
      'PUT /api/notifications/7/read',
      'PUT /api/notifications/read-all',
    ]);
  });
}
