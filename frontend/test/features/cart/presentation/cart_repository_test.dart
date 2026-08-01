import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/cart/data/cart_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('CartRepository.getMyCart', () {
    test('parses a real cart response correctly, including the quantity field', () {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/carts/mine', (options) => FakeResponse({
            'cartId': 7,
            'totalAmount': 240.0,
            'totalItems': 3,
            'items': [
              {
                'cartItemId': 101,
                'productId': 1,
                'productName': 'Amul Milk',
                'productBrand': 'Amul',
                'variantId': 10,
                // quantity (how many in cart) and variantQuantity (pack
                // size, e.g. "1 litre") are DIFFERENT fields - this test
                // exists specifically because they were once confused with
                // each other (see git history / earlier session notes).
                'quantity': 3,
                'variantQuantity': 1.0,
                'unit': 'L',
                'imageUrl': null,
                'price': 60.0,
                'totalPrice': 180.0,
                'mrp': 65.0,
                'available': true,
              },
            ],
          }));

      final repository = CartRepository(apiClient: buildTestApiClient(adapter));

      expect(
        repository.getMyCart().then((cart) {
          expect(cart.cartId, 7);
          expect(cart.totalItems, 3);
          expect(cart.items, hasLength(1));

          final item = cart.items.first;
          // The actual regression check: quantity must be 3 (how many are
          // in the cart), NOT 1 (which is the pack size / variantQuantity).
          expect(item.quantity, 3);
          expect(item.variantQuantity, 1.0);
          expect(item.productName, 'Amul Milk');
        }),
        completes,
      );
    });
  });

  group('CartRepository.updateItemQuantity', () {
    test('sends the exact new quantity, not an additive delta', () async {
      final adapter = FakeHttpClientAdapter();
      late Map<String, dynamic> capturedQuery;

      adapter.on('PUT', '/api/carts/items/101', (options) {
        capturedQuery = options.queryParameters;
        return const FakeResponse({
          'cartId': 7,
          'totalAmount': 120.0,
          'totalItems': 2,
          'items': [],
        });
      });

      final repository = CartRepository(apiClient: buildTestApiClient(adapter));
      await repository.updateItemQuantity(cartItemId: 101, quantity: 2);

      expect(capturedQuery['quantity'], 2);
    });
  });

  group('CartRepository.removeItem', () {
    test('calls DELETE on the correct cart item path', () async {
      final adapter = FakeHttpClientAdapter();
      var deleteWasCalled = false;

      adapter.on('DELETE', '/api/carts/items/101', (options) {
        deleteWasCalled = true;
        return const FakeResponse({'cartId': 7, 'totalAmount': 0.0, 'totalItems': 0, 'items': []});
      });

      final repository = CartRepository(apiClient: buildTestApiClient(adapter));
      await repository.removeItem(cartItemId: 101);

      expect(deleteWasCalled, isTrue);
    });
  });
}
