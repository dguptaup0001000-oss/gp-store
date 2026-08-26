import 'key_value_store.dart';
import '../logging/app_log.dart';

/// Whether the shop wants new orders spoken aloud.
///
/// Governs the VOICE ONLY. Turning this off must never suppress the banner,
/// the system notification, or the receipt print - a shop that wants quiet
/// still needs to know an order arrived. Nothing outside
/// VoiceAnnouncementService reads this, which is what keeps that true.
///
/// Defaults to ON, including when storage cannot be read: the feature exists
/// because a phone on a counter goes unwatched, so silence is the wrong thing
/// to fall back to.
class VoiceSettings {
  VoiceSettings({KeyValueStore? storage})
      : _storage = storage ?? const SecureKeyValueStore();

  static const _key = 'voice_announcements_enabled';

  final KeyValueStore _storage;

  Future<bool> isEnabled() async {
    try {
      final raw = await _storage.read(_key);
      // Only an explicit "false" disables it. A missing key is a shop that
      // has never touched the setting, which means ON.
      return raw != 'false';
    } catch (e) {
      appLog(
          'Could not read the voice announcement setting, defaulting to on: $e');
      return true;
    }
  }

  Future<void> setEnabled(bool enabled) async {
    try {
      await _storage.write(_key, enabled.toString());
    } catch (e) {
      // Surfaced to the caller so the settings screen can tell the shop the
      // choice did not stick, rather than showing a toggle that silently
      // reverts on the next launch.
      appLog('Could not persist the voice announcement setting: $e');
      rethrow;
    }
  }
}
