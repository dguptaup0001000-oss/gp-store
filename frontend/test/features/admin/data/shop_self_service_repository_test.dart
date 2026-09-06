import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/shop_self_service_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('ShopSelfServiceRepository', () {
    test('asks for its own shop without naming one', () async {
      final adapter = FakeHttpClientAdapter();
      String? capturedPath;
      adapter.on('GET', '/api/shop/profile', (options) {
        capturedPath = options.path;
        return const FakeResponse({
          'id': 1,
          'code': 'GP-1',
          'displayName': 'GP Store',
          'status': 'ACTIVE',
          'maxDeliveryRadiusKm': 5.0,
        });
      });

      final profile =
          await ShopSelfServiceRepository(apiClient: buildTestApiClient(adapter)).profile();

      expect(profile.id, 1);
      expect(capturedPath, '/api/shop/profile',
          reason: 'there is deliberately no /api/shops/{id}/profile - putting '
              'an id in front of a caller is the thing that would need '
              'guarding, so it was never put there');
    });

    test('readiness separates what blocks orders from what only makes them worse', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/shop/readiness', (_) => const FakeResponse({
            'shopId': 2,
            'displayName': 'Sharma Kirana',
            'canTakeOrders': false,
            'steps': [
              {
                'name': 'merchant-active',
                'done': false,
                'blocking': true,
                'detail': 'The business is registered but not switched on yet.',
              },
              {
                'name': 'has-territory',
                'done': false,
                'blocking': false,
                'detail': 'Orders still go out without one.',
              },
              {'name': 'shop-open', 'done': true, 'blocking': true, 'detail': 'Open.'},
            ],
          }));

      final readiness =
          await ShopSelfServiceRepository(apiClient: buildTestApiClient(adapter)).readiness();

      expect(readiness.canTakeOrders, isFalse);
      expect(readiness.outstandingBlockers.map((s) => s.name), ['merchant-active']);
      expect(readiness.outstandingAdvice.map((s) => s.name), ['has-territory'],
          reason: 'a list that called both "blocked" would train a shopkeeper '
              'to ignore it');
    });

    test('earnings carry no commission field, because no commission model exists', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/shop/earnings', (_) => const FakeResponse({
            'periodDays': 30,
            'grossSales': 1000.0,
            'refunds': 100.0,
            'netSales': 900.0,
            'collectedOnline': 600.0,
            'collectedCash': 250.0,
            'collectedCodUpi': 50.0,
            'awaitingCollection': 100.0,
            'orderCount': 12,
            'cancelledCount': 1,
          }));

      final earnings =
          await ShopSelfServiceRepository(apiClient: buildTestApiClient(adapter))
              .earnings(days: 30);

      expect(earnings.netSales, 900.0);
      expect(earnings.collectedCash, 250.0,
          reason: 'the split matters - it is money a rider is currently '
              'carrying, which is what a shopkeeper reconciles at day end');
      // Decision W2 is open. Nothing here computes or displays a fee, and no
      // field exists to hold one.
    });

    test('open work comes back as a status count map', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/shop/open-work',
          (_) => const FakeResponse({'PACKING': 3, 'CONFIRMED': 1, 'DELIVERED': 0}));

      final work =
          await ShopSelfServiceRepository(apiClient: buildTestApiClient(adapter)).openWork();

      expect(work['PACKING'], 3);
      expect(work['DELIVERED'], 0);
    });
  });
}
