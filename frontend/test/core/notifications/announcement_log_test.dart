import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/notifications/announcement_log.dart';

import 'fakes.dart';

/// The dedup rule is the part of this feature a shopkeeper would notice
/// breaking: an order announced twice sounds like two orders, and a busy
/// counter would act on the phantom one.
void main() {
  group('AnnouncementLog.claim', () {
    test('the first claim on an order wins and the second does not', () async {
      final log = AnnouncementLog(storage: FakeKeyValueStore());

      expect(await log.claim('4821'), isTrue);
      expect(await log.claim('4821'), isFalse);
      expect(await log.claim('4821'), isFalse);
    });

    test('different orders are independent', () async {
      final log = AnnouncementLog(storage: FakeKeyValueStore());

      expect(await log.claim('1'), isTrue);
      expect(await log.claim('2'), isTrue);
      expect(await log.claim('1'), isFalse);
      expect(await log.claim('3'), isTrue);
    });

    test('survives the app restarting, because the record is persisted', () async {
      // Same storage, new AnnouncementLog: exactly what a relaunch looks
      // like to this class, and the case an in-memory set would fail.
      final storage = FakeKeyValueStore();

      expect(await AnnouncementLog(storage: storage).claim('900'), isTrue);
      expect(await AnnouncementLog(storage: storage).claim('900'), isFalse);
    });

    test('two simultaneous deliveries of one order still announce it once', () async {
      // The real race: FCM delivers twice, or the background isolate and the
      // foreground listener both handle the same push. Without the lock both
      // would read the pre-write list and both would return true.
      final storage = FakeKeyValueStore()..readDelay = const Duration(milliseconds: 5);
      final log = AnnouncementLog(storage: storage);

      final results = await Future.wait([log.claim('77'), log.claim('77')]);

      expect(results.where((won) => won).length, 1);
    });

    test('a whitespace-padded id is the same order', () async {
      final log = AnnouncementLog(storage: FakeKeyValueStore());

      expect(await log.claim('55'), isTrue);
      expect(await log.claim('  55  '), isFalse);
    });

    test('an empty id is refused rather than announced', () async {
      // Nothing about it can be deduplicated, so announcing it risks a line
      // that repeats on every redelivery forever.
      final log = AnnouncementLog(storage: FakeKeyValueStore());

      expect(await log.claim(''), isFalse);
      expect(await log.claim('   '), isFalse);
    });

    test('a storage failure allows the announcement rather than losing it', () async {
      // Deliberate trade: a shop hearing one order twice is a far smaller
      // problem than a shop never hearing a real order.
      final log = AnnouncementLog(storage: FakeKeyValueStore(failReads: true));

      expect(await log.claim('123'), isTrue);
    });

    test('remembers far more orders than a shop takes in a day, and stays bounded', () async {
      final storage = FakeKeyValueStore();
      final log = AnnouncementLog(storage: storage);

      for (var id = 1; id <= 250; id++) {
        expect(await log.claim('$id'), isTrue);
      }

      final remembered = storage.values['voice_announced_order_ids']!.split(',');
      expect(remembered.length, 200);
      // The most recent are the ones that could still be redelivered.
      expect(remembered.last, '250');
      expect(remembered.contains('51'), isTrue);
      expect(remembered.contains('50'), isFalse);
    });
  });
}
