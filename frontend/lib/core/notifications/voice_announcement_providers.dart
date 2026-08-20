import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'voice_announcement_service.dart';

/// One TTS engine for the app's lifetime.
///
/// Not created per announcement: instantiating an engine costs a platform
/// channel round trip and re-running the language/rate setup, which on a busy
/// counter would be paid on every order.
final voiceAnnouncementServiceProvider = Provider<VoiceAnnouncementService>((ref) {
  final service = VoiceAnnouncementService();
  ref.onDispose(service.stop);
  return service;
});
