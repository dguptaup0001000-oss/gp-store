import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/notifications/announcement_log.dart';
import 'package:gpstore/core/notifications/voice_announcement_service.dart';
import 'package:gpstore/core/notifications/voice_settings.dart';

import 'fakes.dart';

/// End-to-end over the announcer itself: what actually reaches the speaker,
/// in what order, and what never reaches it at all.
void main() {
  late FakeSpeechEngine engine;
  late FakeKeyValueStore storage;
  late VoiceAnnouncementService service;

  VoiceAnnouncementService build() => VoiceAnnouncementService(
        engine: engine,
        log: AnnouncementLog(storage: storage),
        settings: VoiceSettings(storage: storage),
      );

  setUp(() {
    engine = FakeSpeechEngine();
    storage = FakeKeyValueStore();
    service = build();
  });

  Future<void> announce(String id, String name, String amount) =>
      service.announceNewOrder(orderId: id, customerName: name, rupees: amount);

  group('announceNewOrder', () {
    test('speaks the backend name and amount', () async {
      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken, ['New order received from Ramesh Kumar. Order amount 520 rupees.']);
    });

    test('announces the same order only once, however often it is delivered', () async {
      await announce('1', 'Ramesh Kumar', '520');
      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken.length, 1);
    });

    test('two orders that look identical are both announced', () async {
      // Same name, same amount, different order - the exact case that name+
      // amount deduplication would wrongly silence.
      await announce('1', 'Ramesh Kumar', '520');
      await announce('2', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken.length, 2);
    });

    test('three orders in a burst are spoken one at a time, in arrival order', () async {
      engine.speakDuration = const Duration(milliseconds: 20);

      await announce('1', 'Asha', '100');
      await announce('2', 'Bilal', '200');
      await announce('3', 'Chitra', '300');
      await service.drainForTest();

      expect(engine.sawOverlap, isFalse);
      expect(engine.spoken, [
        'New order received from Asha. Order amount 100 rupees.',
        'New order received from Bilal. Order amount 200 rupees.',
        'New order received from Chitra. Order amount 300 rupees.',
      ]);
    });

    test('says nothing when the shop has turned announcements off', () async {
      await VoiceSettings(storage: storage).setEnabled(false);

      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken, isEmpty);
    });

    test('an order that arrived while muted can still be announced once unmuted', () async {
      // The id must not be burned by a delivery that was never spoken,
      // otherwise a redelivery after unmuting would be silently dropped.
      await VoiceSettings(storage: storage).setEnabled(false);
      await announce('1', 'Ramesh Kumar', '520');

      await VoiceSettings(storage: storage).setEnabled(true);
      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken.length, 1);
    });

    test('a malformed payload is silent and does not burn the order id', () async {
      // A serialisation bug that sends "null" must not cost the order its one
      // chance to be announced when a good copy of the push arrives.
      await announce('1', 'null', '520');
      await service.drainForTest();
      expect(engine.spoken, isEmpty);

      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();
      expect(engine.spoken.length, 1);
    });

    test('a push with no order id is never announced', () async {
      await announce('', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken, isEmpty);
    });

    test('a dead TTS engine does not throw at the caller', () async {
      engine.failSpeak = true;

      await expectLater(announce('1', 'Ramesh Kumar', '520'), completes);
      await service.drainForTest();

      expect(engine.spoken, isEmpty);
    });

    test('one failed line does not mute every order after it', () async {
      engine.failSpeak = true;
      await announce('1', 'Asha', '100');
      await service.drainForTest();

      engine.failSpeak = false;
      await announce('2', 'Bilal', '200');
      await service.drainForTest();

      expect(engine.spoken, ['New order received from Bilal. Order amount 200 rupees.']);
    });

    test('speaks even when the engine refuses to be configured', () async {
      // Default voice settings beat no voice at all.
      engine.failConfigure = true;

      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();

      expect(engine.spoken.length, 1);
    });

    test('configures the engine once, not once per order', () async {
      await announce('1', 'Asha', '100');
      await announce('2', 'Bilal', '200');
      await service.drainForTest();

      expect(engine.configureCalls, 1);
    });

    test('a relaunched app does not re-announce an order it already spoke', () async {
      await announce('1', 'Ramesh Kumar', '520');
      await service.drainForTest();

      // Same storage, fresh service: what a restart looks like from here.
      final afterRestart = build();
      await afterRestart.announceNewOrder(orderId: '1', customerName: 'Ramesh Kumar', rupees: '520');
      await afterRestart.drainForTest();

      expect(engine.spoken.length, 1);
    });
  });
}
