import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/directory_models.dart';

void main() {
  group('A merchant row', () {
    test('names itself by display name, then legal name, then reference', () {
      expect(
          MerchantHit.fromJson({'id': 7, 'merchantRef': 'M-7', 'displayName': 'Deepak Phone Shop', 'legalName': 'Deepak Traders'})
              .title,
          'Deepak Phone Shop');
      expect(
          MerchantHit.fromJson({'id': 7, 'merchantRef': 'M-7', 'legalName': 'Deepak Traders'}).title,
          'Deepak Traders');
      expect(MerchantHit.fromJson({'id': 7, 'merchantRef': 'M-7'}).title, 'M-7',
          reason: 'a row with no name must still identify itself, never be blank');
    });

    test('a blank name is not a name', () {
      expect(
          MerchantHit.fromJson({'id': 7, 'merchantRef': 'M-7', 'displayName': '   ', 'legalName': 'Real Name'})
              .title,
          'Real Name');
    });

    test('the shop count reads as a sentence, and one is singular', () {
      expect(MerchantHit.fromJson({'id': 1, 'merchantRef': 'M-1', 'shopCount': 1}).shopsLabel, '1 shop');
      expect(MerchantHit.fromJson({'id': 1, 'merchantRef': 'M-1', 'shopCount': 4}).shopsLabel, '4 shops');
      expect(MerchantHit.fromJson({'id': 1, 'merchantRef': 'M-1'}).shopsLabel, '0 shops');
    });

    test('a missing reference is derived rather than left null', () {
      expect(MerchantHit.fromJson({'id': 42}).merchantRef, 'M-42');
    });
  });

  group('A customer row', () {
    test('names itself, or falls back to the reference', () {
      expect(CustomerHit.fromJson({'id': 3, 'customerRef': 'C-3', 'name': 'Anita'}).title, 'Anita');
      expect(CustomerHit.fromJson({'id': 3, 'customerRef': 'C-3'}).title, 'C-3');
    });

    test('order count is singular at one', () {
      expect(CustomerHit.fromJson({'id': 1, 'customerRef': 'C-1', 'orderCount': 1}).ordersLabel, '1 order');
      expect(CustomerHit.fromJson({'id': 1, 'customerRef': 'C-1', 'orderCount': 0}).ordersLabel, '0 orders');
    });
  });

  group('A page of results', () {
    test('the total is the server\'s, not the page length', () {
      final page = DirectoryPage.fromJson<CustomerHit>({
        'content': [
          {'id': 1, 'customerRef': 'C-1'},
          {'id': 2, 'customerRef': 'C-2'},
        ],
        'page': 0,
        'size': 2,
        'totalElements': 480,
        'totalPages': 240,
      }, CustomerHit.fromJson);

      expect(page.content.length, 2);
      expect(page.totalElements, 480,
          reason: 'counting content.length would say "2 matches" for every '
              'search on a marketplace of any size');
      expect(page.hasMore, isTrue);
    });

    test('the last page knows it is the last', () {
      final page = DirectoryPage.fromJson<CustomerHit>(
          {'content': [], 'page': 3, 'size': 20, 'totalElements': 60, 'totalPages': 3},
          CustomerHit.fromJson);
      expect(page.hasMore, isFalse);
      expect(page.isEmpty, isTrue);
    });

    test('a malformed response is an empty page, not a crash', () {
      final page = DirectoryPage.fromJson<CustomerHit>({}, CustomerHit.fromJson);
      expect(page.isEmpty, isTrue);
      expect(page.totalElements, 0);
    });
  });

  group('Merchant activity', () {
    test('says plainly that active time is not measured', () {
      final activity = MerchantActivity.fromJson({
        'sessionsMeasured': false,
        'note': 'GP-STORE does not time merchant or staff app usage',
        'activeDaysInWindow': 12,
      });
      expect(activity.sessionsMeasured, isFalse);
      expect(activity.note, isNotEmpty,
          reason: 'a screen that drew a zero here would say the merchant did '
              'nothing, when the truth is that nobody looked');
    });

    test('last activity is the most recent real event, whichever it was', () {
      final activity = MerchantActivity.fromJson({
        'lastOrderAt': '2026-01-10T10:00:00',
        'lastListingUpdateAt': '2026-03-02T09:00:00',
        'lastAdminEventAt': '2026-02-01T08:00:00',
      });
      expect(activity.lastActivityAt, DateTime.parse('2026-03-02T09:00:00'));
    });

    test('a merchant with no events at all has no last activity', () {
      expect(MerchantActivity.fromJson({}).lastActivityAt, isNull,
          reason: 'null means "no record", and must not become today');
    });
  });

  group('Customer activity', () {
    test('time reads in hours and minutes, never raw seconds', () {
      expect(CustomerActivity.fromJson({'totalSeconds': 0}).totalTimeLabel, '—');
      expect(CustomerActivity.fromJson({'totalSeconds': 90}).totalTimeLabel, '1m');
      expect(CustomerActivity.fromJson({'totalSeconds': 3600}).totalTimeLabel, '1h');
      expect(CustomerActivity.fromJson({'totalSeconds': 15120}).totalTimeLabel, '4h 12m');
    });

    test('carries the note saying it is not evidence', () {
      final activity = CustomerActivity.fromJson({
        'sessions': 9,
        'totalSeconds': 600,
        'sessionsMeasured': true,
        'note': 'reported by the customer\'s own phone and capped by the server',
      });
      expect(activity.sessionsMeasured, isTrue);
      expect(activity.note, contains('capped'));
    });
  });

  group('Where a customer buys', () {
    test('buying often is NOT a preference', () {
      final frequent = ShopAffinity.fromJson({
        'shopId': 5,
        'shopName': 'GP Store',
        'orders': 28,
        'preferred': false,
      });
      expect(frequent.orders, 28);
      expect(frequent.preferred, isFalse,
          reason: 'twenty-eight orders may mean they like the shop, or only '
              'that it is the one that delivers to their street - calling that '
              '"preferred" puts words in the customer\'s mouth');
    });

    test('an explicitly saved shop is preferred even on one order', () {
      final chosen = ShopAffinity.fromJson({
        'shopId': 9,
        'shopName': 'Raju Kirana',
        'orders': 1,
        'preferred': true,
      });
      expect(chosen.orders, 1);
      expect(chosen.preferred, isTrue);
    });

    test('preference defaults to false when the server did not say', () {
      expect(ShopAffinity.fromJson({'shopId': 1}).preferred, isFalse,
          reason: 'the safe default for a claim about somebody is not making it');
    });
  });

  group('An audit entry', () {
    test('renders a transition when there was one', () {
      final entry = AuditEntry.fromJson({
        'id': 1,
        'action': 'MERCHANT_STATUS_CHANGED',
        'previousState': 'PENDING_REVIEW',
        'newState': 'ACTIVE',
      });
      expect(entry.transition, 'PENDING_REVIEW → ACTIVE');
    });

    test('an event that is not a transition has none', () {
      expect(AuditEntry.fromJson({'id': 1, 'action': 'PLATFORM_GLOBAL_SEARCH'}).transition,
          isNull);
    });

    test('a one-sided transition still reads', () {
      expect(AuditEntry.fromJson({'id': 1, 'newState': 'SUSPENDED'}).transition,
          '— → SUSPENDED');
    });
  });
}
