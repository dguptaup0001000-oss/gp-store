import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/presentation/auth_providers.dart';
import 'admin_order_sound_watcher.dart';
import 'push_notification_providers.dart';
import 'voice_announcement_providers.dart';

/// Admin APK only. The customer graph must not import this file.
final adminOrderSoundWatcherProvider = Provider<AdminOrderSoundWatcher>((ref) {
  final watcher = AdminOrderSoundWatcher(
    apiClient: ref.watch(apiClientProvider),
    voice: ref.watch(voiceAnnouncementServiceProvider),
    push: ref.watch(pushNotificationServiceProvider),
  );
  ref.onDispose(watcher.stop);
  return watcher;
});
