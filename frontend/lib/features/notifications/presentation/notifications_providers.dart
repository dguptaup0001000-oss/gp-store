import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/notifications_repository.dart';
import '../domain/notification_models.dart';

final notificationsRepositoryProvider = Provider<NotificationsRepository>((ref) {
  return NotificationsRepository(apiClient: ref.watch(apiClientProvider));
});

typedef MyNotificationsPage = ({List<AppNotification> notifications, int page, int totalPages});

/// Paginated - see NotificationsRepository.getMyNotifications's doc comment.
/// AsyncNotifier (not a plain FutureProvider) so loadMore() can append to
/// the existing state.
class MyNotificationsController extends AsyncNotifier<MyNotificationsPage> {
  @override
  Future<MyNotificationsPage> build() async {
    final result = await ref.read(notificationsRepositoryProvider).getMyNotifications(page: 0);
    return (notifications: result.notifications, page: 0, totalPages: result.totalPages);
  }

  bool get hasMore {
    final current = state.valueOrNull;
    return current != null && current.page + 1 < current.totalPages;
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.page + 1 >= current.totalPages) return;

    final nextPage = current.page + 1;
    final result = await ref.read(notificationsRepositoryProvider).getMyNotifications(page: nextPage);
    state = AsyncData((
      notifications: [...current.notifications, ...result.notifications],
      page: nextPage,
      totalPages: result.totalPages,
    ));
  }
}

final myNotificationsProvider = AsyncNotifierProvider<MyNotificationsController, MyNotificationsPage>(
  MyNotificationsController.new,
);

/// A dedicated count query, not derived from myNotificationsProvider - that
/// only ever holds one page of notifications now, not the full history.
final unreadNotificationCountProvider = FutureProvider<int>((ref) {
  return ref.watch(notificationsRepositoryProvider).getUnreadCount();
});
