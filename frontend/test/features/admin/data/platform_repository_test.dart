import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('PlatformRepository', () {
    test('lists merchants with the status that decides whether they trade', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/merchants', (_) => const FakeResponse([
            {'id': 1, 'legalName': 'GP Retail', 'displayName': 'GP Store', 'status': 'ACTIVE'},
            {'id': 2, 'displayName': 'Sharma Kirana', 'status': 'APPROVED'},
          ]));

      final merchants =
          await PlatformRepository(apiClient: buildTestApiClient(adapter)).merchants();

      expect(merchants, hasLength(2));
      expect(merchants.first.isTrading, isTrue);
      expect(merchants.last.isTrading, isFalse,
          reason: 'APPROVED means the papers are in order; ACTIVE means the '
              'business is trading, and a shop under an approved-but-not-active '
              'merchant sells nothing');
    });

    test('a status change carries the reason it was made for', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('PUT', '/api/platform/merchants/2/status', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({'id': 2, 'status': 'APPROVED'});
      });

      await PlatformRepository(apiClient: buildTestApiClient(adapter)).setMerchantStatus(
        merchantId: 2,
        status: 'APPROVED',
        reason: 'papers checked',
      );

      expect(sent!['status'], 'APPROVED');
      expect(sent!['reason'], 'papers checked',
          reason: 'a status change nobody recorded a reason for is one nobody '
              'can account for later');
    });

    test('the market overview is one line per shop, never a pooled total', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/overview', (_) => const FakeResponse({
            'periodDays': 30,
            'shops': [
              {'shopId': 1, 'orderCount': 10, 'grossSales': 1000.0, 'netSales': 950.0},
              {'shopId': 2, 'orderCount': 2, 'grossSales': 200.0, 'netSales': 200.0},
            ],
            'totals': {'orderCount': 12, 'grossSales': 1200.0, 'netSales': 1150.0,
                       'tradingShops': 2},
            'shopCount': 3,
            'merchantCount': 3,
          }));

      final overview =
          await PlatformRepository(apiClient: buildTestApiClient(adapter)).overview();

      expect(overview.shops, hasLength(2));
      expect(overview.shops.first.shopId, 1);
      expect(overview.totals!.tradingShops, 2);
      expect(overview.shopCount, 3,
          reason: 'three shops exist and two traded - a roll-up that only '
              'counted the ones with orders would hide a shop taking none');
    });
  });
}
