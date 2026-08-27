import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/presentation/auth_providers.dart';
import 'admin_order_sound_watcher.dart';
import 'push_notification_service.dart';
import 'voice_announcement_providers.dart';

final pushNotificationServiceProvider =
    Provider<PushNotificationService>((ref) {
  return PushNotificationService(apiClient: ref.watch(apiClientProvider));
});

final adminOrderSoundWatcherProvider = Provider<AdminOrderSoundWatcher>((ref) {
  final watcher = AdminOrderSoundWatcher(
    apiClient: ref.watch(apiClientProvider),
    voice: ref.watch(voiceAnnouncementServiceProvider),
    push: ref.watch(pushNotificationServiceProvider),
  );
  ref.onDispose(watcher.stop);
  return watcher;
});
