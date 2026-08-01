import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/wishlist/data/wishlist_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('WishlistRepository.getMyWishlist', () {
    test('parses wishlist items with their nested product', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/wishlists/mine', (options) => FakeResponse([
            {
              'id': 55,
              'product': {'id': 9, 'name': 'Basmati Rice', 'variants': []},
            },
          ]));

      final repository = WishlistRepository(apiClient: buildTestApiClient(adapter));
      final items = await repository.getMyWishlist();

      expect(items, hasLength(1));
      // id (55) is the WISHLIST ENTRY's own id, distinct from the product's
      // id (9) - removeFromWishlist needs the former, never the latter.
      // This distinction is exactly what caused the need for a manual
      // null-safe lookup instead of matching on product id alone.
      expect(items.first.id, 55);
      expect(items.first.product?.id, 9);
      expect(items.first.product?.name, 'Basmati Rice');
    });
  });

  group('WishlistRepository.removeFromWishlist', () {
    test('deletes by the WISHLIST ITEM id, not the product id', () async {
      final adapter = FakeHttpClientAdapter();
      String? calledPath;

      adapter.on('DELETE', '/api/wishlists/55', (options) {
        calledPath = options.path;
        return const FakeResponse(null);
      });

      final repository = WishlistRepository(apiClient: buildTestApiClient(adapter));
      await repository.removeFromWishlist(55);

      expect(calledPath, '/api/wishlists/55');
    });
  });

  group('WishlistRepository.addToWishlist', () {
    test('sends the product id nested under "product", matching backend shape', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? capturedBody;

      adapter.on('POST', '/api/wishlists', (options) {
        capturedBody = options.data as Map<String, dynamic>;
        return const FakeResponse({
          'id': 60,
          'product': {'id': 9, 'name': 'Basmati Rice', 'variants': []},
        });
      });

      final repository = WishlistRepository(apiClient: buildTestApiClient(adapter));
      await repository.addToWishlist(9);

      expect(capturedBody, {
        'product': {'id': 9},
      });
    });
  });
}
