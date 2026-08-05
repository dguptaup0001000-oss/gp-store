import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import '../data/notifications_repository.dart';
import '../domain/notification_models.dart';

final notificationsRepositoryProvider = Provider<NotificationsRepository>((ref) {
  return NotificationsRepository(apiClient: ref.watch(apiClientProvider));
});

final myNotificationsProvider = FutureProvider<List<AppNotification>>((ref) {
  // See myProfileProvider's comment (profile_providers.dart) - without this
  // watch, a different account logging in on the same device without a full
  // app restart would keep seeing the PREVIOUS customer's notifications.
  ref.watch(authControllerProvider);
  return ref.watch(notificationsRepositoryProvider).getMyNotifications();
});

/// Derived from myNotificationsProvider rather than a separate API call -
/// no reason to fetch the same list twice just to count it.
final unreadNotificationCountProvider = Provider<int>((ref) {
  return ref.watch(myNotificationsProvider).maybeWhen(
        data: (notifications) => notifications.where((n) => !n.isRead).length,
        orElse: () => 0,
      );
});
