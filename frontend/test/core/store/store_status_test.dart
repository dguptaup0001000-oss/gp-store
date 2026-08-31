import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/store/store_status.dart';
import 'package:gpstore/core/store/store_status_copy.dart';

/// What the app says about the shop's hours, and the one thing it may never say.
void main() {
  StoreStatus status({
    bool acceptingOrders = true,
    StoreMode mode = StoreMode.sameDay,
    StoreDeliveryType deliveryType = StoreDeliveryType.sameDay,
    DateTime? deliveryDate,
    int? countdownSeconds,
    bool closedToday = false,
    String? message,
  }) {
    return StoreStatus(
      browsingOpen: true,
      acceptingOrders: acceptingOrders,
      mode: mode,
      deliveryType: deliveryType,
      deliveryDate: deliveryDate,
      deliveryStartTime: '09:00',
      deliveryEndTime: '21:00',
      closedToday: closedToday,
      message: message,
      countdownSeconds: countdownSeconds,
    );
  }

  group('the shop is never described as closed', () {
    test('browsing is open in every state this app can represent', () {
      // The rule the whole feature rests on. If any of these ever reads
      // false, the catalogue disappears at three in the morning.
      for (final s in [
        status(),
        status(mode: StoreMode.night, deliveryType: StoreDeliveryType.nextMorning),
        status(acceptingOrders: false, message: 'Stocktake'),
        status(closedToday: true),
        StoreStatus.unknown(),
      ]) {
        expect(StoreStatusCopy.browsingNeverCloses(s), isTrue);
      }
    });

    test('the night banner invites an order rather than announcing a closure', () {
      final banner = StoreStatusCopy.banner(status(
        mode: StoreMode.night,
        deliveryType: StoreDeliveryType.nextMorning,
      ));
      expect(banner, isNotNull);
      expect(banner!.toLowerCase(), isNot(contains('closed')));
      expect(banner.toLowerCase(), isNot(contains('shut')));
      expect(banner, contains('9 AM'));
    });

    test('an unreachable shop is assumed open, and promises nothing', () {
      // Failing open: the server refuses an order it cannot accept anyway,
      // so the cost of being wrong this way is one message at checkout. The
      // cost of the other way is a customer who leaves.
      final unknown = StoreStatus.unknown();
      expect(unknown.browsingOpen, isTrue);
      expect(unknown.acceptingOrders, isTrue);
      expect(StoreStatusCopy.deliveryPromise(unknown), isNull);
      expect(StoreStatusCopy.banner(unknown), isNull);
    });
  });

  group('the delivery promise', () {
    test('a 7am order is delivered TODAY, and must not say tomorrow', () {
      // The most likely bug in the whole feature: reading "next morning" as
      // "tomorrow". An order placed before opening goes out at 9am the SAME
      // day, and telling that customer "tomorrow" loses the sale.
      final today = DateTime.now();
      final promise = StoreStatusCopy.deliveryPromise(status(
        mode: StoreMode.night,
        deliveryType: StoreDeliveryType.nextMorning,
        deliveryDate: DateTime(today.year, today.month, today.day),
      ));
      expect(promise, 'Arriving from 9 AM');
      expect(promise, isNot(contains('tomorrow')));
    });

    test('a 10pm order says tomorrow', () {
      final tomorrow = DateTime.now().add(const Duration(days: 1));
      final promise = StoreStatusCopy.deliveryPromise(status(
        mode: StoreMode.night,
        deliveryType: StoreDeliveryType.nextMorning,
        deliveryDate: DateTime(tomorrow.year, tomorrow.month, tomorrow.day),
      ));
      expect(promise, 'Arriving from 9 AM tomorrow');
    });

    test('a delivery further out is named by its day', () {
      final later = DateTime.now().add(const Duration(days: 3));
      final promise = StoreStatusCopy.deliveryPromise(status(
        mode: StoreMode.night,
        deliveryType: StoreDeliveryType.nextMorning,
        deliveryDate: DateTime(later.year, later.month, later.day),
      ));
      expect(promise, contains('on '));
      expect(promise, isNot(contains('tomorrow')));
    });

    test('same-day says today', () {
      expect(StoreStatusCopy.deliveryPromise(status()), 'Arriving today');
    });

    test('nothing is promised when the server did not say', () {
      expect(
        StoreStatusCopy.deliveryPromise(
            status(deliveryType: StoreDeliveryType.unknown)),
        isNull,
      );
    });
  });

  group('the closing countdown', () {
    test('counts in whole minutes above a minute', () {
      expect(StoreStatusCopy.countdown(status(countdownSeconds: 900)), '15 min');
      expect(StoreStatusCopy.countdown(status(countdownSeconds: 61)), '2 min');
      expect(StoreStatusCopy.countdown(status(countdownSeconds: 60)), '1 min');
    });

    test('counts in seconds below a minute', () {
      expect(StoreStatusCopy.countdown(status(countdownSeconds: 30)), '30 sec');
    });

    test('is inactive when the server sent no countdown', () {
      expect(status().countdownActive, isFalse);
      expect(status().countdownSeconds, isNull);
    });

    test('never goes negative', () {
      // A banner reading "closes in -3 minutes" is worse than one that has
      // stopped. Zero is the floor and it turns the warning off.
      final s = status(countdownSeconds: 0);
      expect(s.countdownSeconds, 0);
      expect(s.countdownActive, isFalse);
    });

    test('the banner shows the countdown, and still allows the order', () {
      // The countdown is a WARNING, not a cutoff - the shop is still taking
      // same-day orders right up to the minute.
      final s = status(countdownSeconds: 600);
      expect(StoreStatusCopy.banner(s), 'Same-day delivery closes in 10 min');
      expect(s.acceptingOrders, isTrue);
    });
  });

  group('paused orders', () {
    test("the shop's own words are shown, not a generic error", () {
      final s = status(acceptingOrders: false, message: 'Back at 9am');
      expect(StoreStatusCopy.banner(s), 'Back at 9am');
    });

    test('a pause still invites browsing', () {
      final s = status(acceptingOrders: false);
      expect(StoreStatusCopy.bannerDetail(s), contains('browse'));
    });
  });

  group('order history labels', () {
    test('read the stored type, and say nothing when there is none', () {
      // Orders placed before the shop had delivery windows carry no type.
      // Inventing one would state a fact about a delivery that already
      // happened.
      expect(StoreStatusCopy.historyLabel('SAME_DAY'), 'Same-day delivery');
      expect(StoreStatusCopy.historyLabel('NEXT_MORNING'), 'Next-morning delivery');
      expect(StoreStatusCopy.historyLabel(null), isNull);
      expect(StoreStatusCopy.historyLabel('SOMETHING_NEW'), isNull);
    });
  });

  group('parsing', () {
    test('a missing browsingOpen defaults to open, never to closed', () {
      // A serialisation mismatch must not hide the entire catalogue.
      final s = StoreStatus.fromJson(<String, dynamic>{});
      expect(s.browsingOpen, isTrue);
      expect(s.acceptingOrders, isTrue);
    });

    test('unrecognised enum values fall back rather than throwing', () {
      final s = StoreStatus.fromJson({
        'mode': 'FUTURE_MODE',
        'deliveryType': 'FUTURE_TYPE',
      });
      expect(s.mode, StoreMode.unknown);
      expect(s.deliveryType, StoreDeliveryType.unknown);
    });

    test('reads a real response', () {
      final s = StoreStatus.fromJson({
        'browsingOpen': true,
        'acceptingOrders': true,
        'mode': 'NIGHT',
        'deliveryType': 'NEXT_MORNING',
        'deliveryDate': '2026-03-02',
        'deliveryStartTime': '09:00',
        'deliveryEndTime': '21:00',
        'countdownSeconds': null,
        'closedToday': false,
        'message': null,
      });
      expect(s.mode, StoreMode.night);
      expect(s.deliveryType, StoreDeliveryType.nextMorning);
      expect(s.deliveryDate, DateTime(2026, 3, 2));
      expect(s.countdownActive, isFalse);
    });

    test('the hours come from the server, not from a constant in the app', () {
      // Changing the shop's hours must not need a new release.
      final s = StoreStatus.fromJson({
        'mode': 'NIGHT',
        'deliveryType': 'NEXT_MORNING',
        'deliveryStartTime': '10:30',
      });
      expect(StoreStatusCopy.banner(s), contains('10:30 AM'));
    });
  });
}
