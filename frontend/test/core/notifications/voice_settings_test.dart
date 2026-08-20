import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/notifications/voice_settings.dart';

import 'fakes.dart';

void main() {
  group('VoiceSettings', () {
    test('is on for a shop that has never touched the setting', () async {
      expect(await VoiceSettings(storage: FakeKeyValueStore()).isEnabled(), isTrue);
    });

    test('stays off across a restart once turned off', () async {
      final storage = FakeKeyValueStore();

      await VoiceSettings(storage: storage).setEnabled(false);

      expect(await VoiceSettings(storage: storage).isEnabled(), isFalse);
    });

    test('can be turned back on', () async {
      final storage = FakeKeyValueStore();
      final settings = VoiceSettings(storage: storage);

      await settings.setEnabled(false);
      await settings.setEnabled(true);

      expect(await settings.isEnabled(), isTrue);
    });

    test('is on when storage cannot be read', () async {
      // The feature exists because an unwatched phone misses orders, so
      // silence is the wrong thing to fall back to.
      expect(await VoiceSettings(storage: FakeKeyValueStore(failReads: true)).isEnabled(), isTrue);
    });

    test('a failed save is reported rather than silently swallowed', () async {
      // Otherwise the switch would show a choice that reverts on next launch.
      final settings = VoiceSettings(storage: FakeKeyValueStore(failWrites: true));

      expect(() => settings.setEnabled(false), throwsA(isA<StateError>()));
    });
  });
}
