import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/config/app_environment.dart';
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
    test('sends the scalar productId accepted by WishlistRequest', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? capturedBody;

      adapter.on('POST', '/api/wishlists', (options) {
        expect(options.headers['X-GP-Store-Client-App'], isNotEmpty);
        expect(options.headers['X-GP-Store-Client-Build'], isNotEmpty);
        capturedBody = options.data as Map<String, dynamic>;
        return const FakeResponse({
          'id': 60,
          'product': {'id': 9, 'name': 'Basmati Rice', 'variants': []},
        });
      });

      final repository = WishlistRepository(apiClient: buildTestApiClient(adapter));
      await repository.addToWishlist(9);

      expect(capturedBody, {'productId': 9});
    });
  });

  test('production wire flow survives a new repository session and removes',
      () async {
    final adapter = FakeHttpClientAdapter();
    Map<String, dynamic>? persisted;
    final urls = <String>[];

    adapter.on('POST', '/api/wishlists', (options) {
      urls.add(options.uri.toString());
      expect(options.data, {'productId': 9});
      persisted ??= {
        'id': 55,
        'product': {'id': 9, 'name': 'Motorola Edge 50 Pro', 'variants': []},
      };
      return FakeResponse(persisted);
    });
    adapter.on('GET', '/api/wishlists/mine', (options) {
      urls.add(options.uri.toString());
      return FakeResponse(persisted == null ? const [] : [persisted]);
    });
    adapter.on('DELETE', '/api/wishlists/55', (options) {
      urls.add(options.uri.toString());
      persisted = null;
      return const FakeResponse(null);
    });

    WishlistRepository session() => WishlistRepository(
          apiClient: buildTestApiClient(
            adapter,
            environment: AppEnvironment.production,
          ),
        );

    final firstSession = session();
    expect(await firstSession.getMyWishlist(), isEmpty);
    final firstAdd = await firstSession.addToWishlist(9);
    final repeatedAdd = await firstSession.addToWishlist(9);
    expect(firstAdd.id, repeatedAdd.id,
        reason: 'a repeated tap/request must be idempotent');
    expect((await firstSession.getMyWishlist()).single.product?.id, 9);

    // A fresh repository mirrors closing/reopening or logging in again: no
    // optimistic controller state is available, only the server response.
    final nextSession = session();
    expect((await nextSession.getMyWishlist()).single.product?.id, 9);
    await nextSession.removeFromWishlist(55);
    expect(await nextSession.getMyWishlist(), isEmpty);

    expect(
      urls,
      everyElement(startsWith('https://api.gpstore.co.in/v1/api/wishlists')),
      reason: 'the release URL must contain exactly one /v1 segment',
    );
  });
}
