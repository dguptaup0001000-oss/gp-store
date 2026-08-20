import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'announcement_log.dart';
import 'voice_announcement_service.dart';
import 'voice_settings.dart';

/// The shop's on/off choice, read from secure storage.
///
/// Shared between the service and the settings screen so both see one source
/// of truth. The background isolate necessarily builds its own instance
/// (it cannot reach this container), but that one reads the same storage key,
/// so turning the voice off applies whether the app is open or not.
final voiceSettingsProvider = Provider<VoiceSettings>((ref) => VoiceSettings());

final announcementLogProvider = Provider<AnnouncementLog>((ref) => AnnouncementLog());

/// One TTS engine for the app's lifetime.
///
/// Not created per announcement: instantiating an engine costs a platform
/// channel round trip and re-running the language/rate setup, which on a busy
/// counter would be paid on every order.
final voiceAnnouncementServiceProvider = Provider<VoiceAnnouncementService>((ref) {
  final service = VoiceAnnouncementService(
    log: ref.watch(announcementLogProvider),
    settings: ref.watch(voiceSettingsProvider),
  );
  ref.onDispose(service.stop);
  return service;
});

/// Current state of the toggle, for the settings screen to render.
///
/// autoDispose + invalidate-after-write rather than a StateNotifier holding a
/// cached bool: the value also changes from outside this container (a fresh
/// read in the background isolate), so re-reading storage when the screen
/// opens is the honest thing to show.
final voiceAnnouncementsEnabledProvider = FutureProvider.autoDispose<bool>((ref) {
  return ref.watch(voiceSettingsProvider).isEnabled();
});
