import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/notifications_repository.dart';
import '../domain/notification_models.dart';

final notificationsRepositoryProvider = Provider<NotificationsRepository>((ref) {
  return NotificationsRepository(apiClient: ref.watch(apiClientProvider));
});

typedef MyNotificationsPage = ({
  List<AppNotification> notifications,
  int page,
  int totalPages,
  bool isLoadingMore,
  Object? loadMoreError,
});

/// Paginated - see NotificationsRepository.getMyNotifications's doc comment.
/// AsyncNotifier (not a plain FutureProvider) so loadMore() can append to
/// the existing state.
class MyNotificationsController extends AutoDisposeAsyncNotifier<MyNotificationsPage> {
  bool _loadingMore = false;
  int _requestGeneration = 0;

  @override
  Future<MyNotificationsPage> build() async {
    _requestGeneration++;
    _loadingMore = false;
    final result = await ref.read(notificationsRepositoryProvider).getMyNotifications(page: 0);
    return (
      notifications: result.notifications,
      page: 0,
      totalPages: result.totalPages,
      isLoadingMore: false,
      loadMoreError: null,
    );
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (_loadingMore ||
        state.isLoading ||
        current == null ||
        current.page + 1 >= current.totalPages) {
      return;
    }

    _loadingMore = true;
    final generation = _requestGeneration;
    final nextPage = current.page + 1;
    state = AsyncData((
      notifications: current.notifications,
      page: current.page,
      totalPages: current.totalPages,
      isLoadingMore: true,
      loadMoreError: null,
    ));
    try {
      final result = await ref
          .read(notificationsRepositoryProvider)
          .getMyNotifications(page: nextPage);
      if (generation != _requestGeneration) return;
      final seenIds = current.notifications.map((item) => item.id).toSet();
      final appended = result.notifications
          .where((item) => seenIds.add(item.id))
          .toList(growable: false);
      state = AsyncData((
        notifications: [...current.notifications, ...appended],
        page: nextPage,
        totalPages: result.totalPages,
        isLoadingMore: false,
        loadMoreError: null,
      ));
    } catch (error) {
      if (generation != _requestGeneration) return;
      // Keep the loaded page visible and expose an explicit retry action. The
      // scroll listener may call this again, but it must not create an
      // unhandled asynchronous exception or silently strand the customer.
      state = AsyncData((
        notifications: current.notifications,
        page: current.page,
        totalPages: current.totalPages,
        isLoadingMore: false,
        loadMoreError: error,
      ));
    } finally {
      if (generation == _requestGeneration) _loadingMore = false;
    }
  }
}

final myNotificationsProvider = AsyncNotifierProvider.autoDispose<MyNotificationsController, MyNotificationsPage>(
  MyNotificationsController.new,
);

/// A dedicated count query, not derived from myNotificationsProvider - that
/// only ever holds one page of notifications now, not the full history.
final unreadNotificationCountProvider = FutureProvider<int>((ref) {
  return ref.watch(notificationsRepositoryProvider).getUnreadCount();
});
