import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('MarketplaceRepository.shopsNear', () {
    test('parses the storefronts the backend returns, nearest first', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/marketplace/shops', (_) => const FakeResponse([
            {
              'shopId': 1,
              'code': 'GP-1',
              'displayName': 'GP Store',
              'latitude': 27.16,
              'longitude': 83.94,
              'maxDeliveryRadiusKm': 5.0,
              'distanceKm': 0.4,
              'supportPhone': '9800000000',
              'timeZone': 'Asia/Kolkata',
            },
            {
              'shopId': 2,
              'code': 'GP-2',
              'displayName': 'Sharma Kirana',
              'distanceKm': 1.8,
            },
          ]));

      final shops = await MarketplaceRepository(apiClient: buildTestApiClient(adapter))
          .shopsNear(latitude: 27.16, longitude: 83.94);

      expect(shops, hasLength(2));
      // The ORDER is the backend's - nearest first - and the app keeps it
      // rather than re-sorting by a distance it computed itself.
      expect(shops.first.shopId, 1);
      expect(shops.first.distanceKm, 0.4);
      expect(shops.last.displayName, 'Sharma Kirana');
      // Fields the thin storefront view omits stay null rather than being
      // invented.
      expect(shops.last.supportPhone, isNull);
    });

    test('nobody in range is an empty list, not a failure', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/marketplace/shops', (_) => const FakeResponse([]));

      final shops = await MarketplaceRepository(apiClient: buildTestApiClient(adapter))
          .shopsNear(latitude: 15.0, longitude: 88.0);

      expect(shops, isEmpty,
          reason: 'a customer outside every shop radius is an ordinary state, '
              'and the screen says so rather than showing an error');
    });

    test('no coordinates sends no coordinates', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? query;
      adapter.on('GET', '/api/marketplace/shops', (options) {
        query = Map<String, dynamic>.from(options.queryParameters);
        return const FakeResponse([]);
      });

      await MarketplaceRepository(apiClient: buildTestApiClient(adapter)).shopsNear();

      expect(query, isEmpty,
          reason: 'an address that cannot be proved deliverable is not '
              'deliverable - the app must not send a made-up pin to get a list');
    });
  });

  group('MarketplaceRepository.mode', () {
    test('asks the backend whether this deployment is a marketplace', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/marketplace/mode',
          (_) => const FakeResponse({'mode': 'SINGLE_SHOP', 'multiShop': false}));

      final mode = await MarketplaceRepository(apiClient: buildTestApiClient(adapter)).mode();

      expect(mode.multiShop, isFalse);
      expect(mode.mode, 'SINGLE_SHOP');
    });

    test('a deployment that has not answered is assumed single-shop', () {
      // Guessing "marketplace" would draw a shop switcher over a deployment
      // that has one shop - a screen the customer cannot use and did not ask
      // for. The default therefore matches the current production shape.
      expect(MarketplaceMode.singleShop.multiShop, isFalse);
      expect(MarketplaceMode.singleShop.mode, 'SINGLE_SHOP');
    });
  });
}
