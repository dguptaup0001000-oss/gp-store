import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/notifications/data/notifications_repository.dart';
import 'package:gpstore/features/notifications/domain/notification_models.dart';
import 'package:gpstore/features/notifications/presentation/notifications_providers.dart';

import '../../support/test_api_client.dart';

void main() {
  test('concurrent pagination callbacks issue only one next-page request', () async {
    final repository = _PagingNotificationsRepository()
      ..nextPageCompleter =
          Completer<({List<AppNotification> notifications, int totalPages})>();
    final container = ProviderContainer(overrides: [
      notificationsRepositoryProvider.overrideWithValue(repository),
    ]);
    addTearDown(container.dispose);
    final subscription = container.listen(myNotificationsProvider, (_, __) {});
    addTearDown(subscription.close);

    await _until(() => container.read(myNotificationsProvider).hasValue);
    final controller = container.read(myNotificationsProvider.notifier);
    final first = controller.loadMore();
    final second = controller.loadMore();
    await Future<void>.delayed(Duration.zero);

    expect(repository.requestedPages, [0, 1]);
    expect(container.read(myNotificationsProvider).requireValue.isLoadingMore,
        isTrue);

    repository.nextPageCompleter!.complete(_page(1));
    await Future.wait([first, second]);
    final state = container.read(myNotificationsProvider).requireValue;
    expect(state.notifications, hasLength(2));
    expect(state.page, 1);
    expect(state.isLoadingMore, isFalse);
    expect(repository.requestedPages, [0, 1]);
  });

  test('failed next-page request preserves the page and can be retried',
      () async {
    final repository = _PagingNotificationsRepository()..failNextPageOnce = true;
    final container = ProviderContainer(overrides: [
      notificationsRepositoryProvider.overrideWithValue(repository),
    ]);
    addTearDown(container.dispose);
    final subscription = container.listen(myNotificationsProvider, (_, __) {});
    addTearDown(subscription.close);

    await _until(() => container.read(myNotificationsProvider).hasValue);
    final controller = container.read(myNotificationsProvider.notifier);
    await controller.loadMore();
    var state = container.read(myNotificationsProvider).requireValue;
    expect(state.notifications, hasLength(1));
    expect(state.page, 0);
    expect(state.loadMoreError, isNotNull);
    expect(state.isLoadingMore, isFalse);

    await controller.loadMore();
    state = container.read(myNotificationsProvider).requireValue;
    expect(state.notifications, hasLength(2));
    expect(state.page, 1);
    expect(state.loadMoreError, isNull);
    expect(repository.requestedPages, [0, 1, 1]);
  });

  test('refresh wins over a pagination response that started before it',
      () async {
    final repository = _PagingNotificationsRepository()
      ..nextPageCompleter =
          Completer<({List<AppNotification> notifications, int totalPages})>()
      ..initialPageIds = [1, 99];
    final container = ProviderContainer(overrides: [
      notificationsRepositoryProvider.overrideWithValue(repository),
    ]);
    addTearDown(container.dispose);
    final subscription = container.listen(myNotificationsProvider, (_, __) {});
    addTearDown(subscription.close);

    await _until(() => container.read(myNotificationsProvider).hasValue);
    final oldPagination =
        container.read(myNotificationsProvider.notifier).loadMore();
    await Future<void>.delayed(Duration.zero);

    await container.refresh(myNotificationsProvider.future);
    repository.nextPageCompleter!.complete(_page(1));
    await oldPagination;

    final state = container.read(myNotificationsProvider).requireValue;
    expect(state.notifications.map((item) => item.id), [99]);
    expect(state.page, 0);
    expect(state.isLoadingMore, isFalse);
  });
}

Future<void> _until(bool Function() condition) async {
  for (var attempt = 0; attempt < 50 && !condition(); attempt++) {
    await Future<void>.delayed(Duration.zero);
  }
  expect(condition(), isTrue, reason: 'notification provider did not settle');
}

({List<AppNotification> notifications, int totalPages}) _page(
  int page, {
  int? notificationId,
}) => (
      notifications: [
        AppNotification(
          id: notificationId ?? page + 1,
          title: 'Notification ${notificationId ?? page + 1}',
          message: 'Message ${notificationId ?? page + 1}',
          sentAt: '2026-10-01T10:00:00',
        ),
      ],
      totalPages: 3,
    );

class _PagingNotificationsRepository extends NotificationsRepository {
  _PagingNotificationsRepository()
      : super(apiClient: buildTestApiClient(FakeHttpClientAdapter()));

  final requestedPages = <int>[];
  List<int> initialPageIds = [1];
  int _initialPageCalls = 0;
  Completer<({List<AppNotification> notifications, int totalPages})>?
      nextPageCompleter;
  bool failNextPageOnce = false;

  @override
  Future<({List<AppNotification> notifications, int totalPages})>
      getMyNotifications({int page = 0, int size = 20}) async {
    requestedPages.add(page);
    if (page == 0) {
      final index = _initialPageCalls++;
      final id = initialPageIds[index < initialPageIds.length
          ? index
          : initialPageIds.length - 1];
      return _page(0, notificationId: id);
    }
    final completer = nextPageCompleter;
    if (completer != null) return completer.future;
    if (failNextPageOnce) {
      failNextPageOnce = false;
      throw StateError('temporary notification failure');
    }
    return _page(page);
  }
}
