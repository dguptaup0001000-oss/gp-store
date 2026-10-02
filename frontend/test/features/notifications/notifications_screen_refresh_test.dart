import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/notifications/data/notifications_repository.dart';
import 'package:gpstore/features/notifications/domain/notification_models.dart';
import 'package:gpstore/features/notifications/presentation/notifications_providers.dart';
import 'package:gpstore/features/notifications/presentation/notifications_screen.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  testWidgets('empty notification state can be pulled to refresh',
      (tester) async {
    final repository = _EmptyNotificationsRepository();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        notificationsRepositoryProvider.overrideWithValue(repository),
      ],
      child: const MaterialApp(home: NotificationsScreen()),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No notifications yet'), findsOneWidget);
    expect(repository.pageRequests, 1);

    await tester.drag(find.byType(ListView), const Offset(0, 350));
    await tester.pump();
    await tester.pumpAndSettle();

    expect(repository.pageRequests, 2);
    expect(find.text('No notifications yet'), findsOneWidget);
  });
}

class _EmptyNotificationsRepository extends NotificationsRepository {
  _EmptyNotificationsRepository()
      : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  int pageRequests = 0;

  @override
  Future<({List<AppNotification> notifications, int totalPages})>
      getMyNotifications({int page = 0, int size = 20}) async {
    pageRequests++;
    return (notifications: const <AppNotification>[], totalPages: 0);
  }
}
