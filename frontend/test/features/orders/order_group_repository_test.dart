import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/orders/data/order_group_repository.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('OrderGroupRepository', () {
    test('reads a checkout as one thing with an order per shop', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/orders/groups/42', (_) => const FakeResponse({
            'id': 42,
            'groupNumber': 'GPG2026',
            'totalAmount': 530.0,
            'shopCount': 2,
            'placedAt': '2026-09-06T10:00:00',
            'shopOrders': [
              {
                'orderId': 1,
                'orderNumber': 'GP1',
                'shopId': 7,
                'shopStatus': 'PACKING',
                'paymentStatus': 'COD_PENDING',
                'totalAmount': 300.0,
                'deliveryFee': 20.0,
                'cancellable': true,
              },
              {
                'orderId': 2,
                'orderNumber': 'GP2',
                'shopId': 9,
                'shopStatus': 'OUT_FOR_DELIVERY',
                'totalAmount': 230.0,
                'cancellable': false,
              },
            ],
          }));

      final group =
          await OrderGroupRepository(apiClient: buildTestApiClient(adapter)).checkout(42);

      expect(group.shopCount, 2);
      expect(group.shopOrders, hasLength(2));
      expect(group.shopOrders.first.cancellable, isTrue);
      expect(group.shopOrders.last.cancellable, isFalse,
          reason: 'the button hint is the backend\'s - one shop can still be '
              'stopped while the other\'s rider is at the door');
    });

    test('cancelling a checkout answers per shop, and partial success is normal', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('PUT', '/api/orders/groups/42/cancel', (_) => const FakeResponse({
            'groupId': 42,
            'groupNumber': 'GPG2026',
            'outcomes': [
              {'orderId': 1, 'shopId': 7, 'cancelled': true, 'reason': null},
              {
                'orderId': 2,
                'shopId': 9,
                'cancelled': false,
                'reason': 'This order is already out for delivery.',
              },
            ],
          }));

      final result = await OrderGroupRepository(apiClient: buildTestApiClient(adapter))
          .cancelWholeCheckout(42);

      expect(result.allCancelled, isFalse);
      expect(result.partiallyCancelled, isTrue,
          reason: 'flattening this into one verdict would tell the customer '
              'something untrue about half their money');
      expect(result.refused, hasLength(1));
      expect(result.refused.single.reason, 'This order is already out for delivery.',
          reason: 'the reason shown is the backend\'s own words');
    });

    test('a checkout everything cancelled reports exactly that', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('PUT', '/api/orders/groups/7/cancel', (_) => const FakeResponse({
            'groupId': 7,
            'outcomes': [
              {'orderId': 1, 'shopId': 1, 'cancelled': true},
            ],
          }));

      final result = await OrderGroupRepository(apiClient: buildTestApiClient(adapter))
          .cancelWholeCheckout(7);

      expect(result.allCancelled, isTrue);
      expect(result.partiallyCancelled, isFalse);
      expect(result.refused, isEmpty);
    });

    test('a customer with no split checkouts gets an empty list', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/orders/groups', (_) => const FakeResponse([]));

      final groups =
          await OrderGroupRepository(apiClient: buildTestApiClient(adapter)).myCheckouts();

      expect(groups, isEmpty);
    });
  });
}
