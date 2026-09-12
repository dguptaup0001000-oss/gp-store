import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('the shop header', () {
    test('is absent when nothing has been chosen - which is the shipped app', () async {
      final adapter = FakeHttpClientAdapter();
      RequestOptions? seen;
      adapter.on('GET', '/api/carts/mine', (options) {
        seen = options;
        return const FakeResponse({'items': []});
      });

      final client = buildTestApiClient(adapter, activeShopId: () => null);
      await client.dio.get('/api/carts/mine');

      expect(seen!.headers.containsKey('X-Shop-Id'), isFalse,
          reason: 'a request that names no shop is the normal case and the one '
              'the released app makes - the backend answers Shop #1 under '
              'SINGLE_SHOP without being asked');
    });

    test('is sent when a shop has been chosen', () async {
      final adapter = FakeHttpClientAdapter();
      RequestOptions? seen;
      adapter.on('GET', '/api/carts/mine', (options) {
        seen = options;
        return const FakeResponse({'items': []});
      });

      final client = buildTestApiClient(adapter, activeShopId: () => 42);
      await client.dio.get('/api/carts/mine');

      // It grants nothing. TenantResolver.select can only NARROW a scope the
      // credential already permits, and refuses a shop it does not - so a
      // tampered value cannot widen anything (§78).
      expect(seen!.headers['X-Shop-Id'], '42');
    });

    test('is never attached to an auth endpoint', () async {
      final adapter = FakeHttpClientAdapter();
      RequestOptions? seen;
      adapter.on('POST', '/api/auth/login', (options) {
        seen = options;
        return const FakeResponse({'token': 'x', 'refreshToken': 'y'});
      });

      final client = buildTestApiClient(adapter, activeShopId: () => 42);
      await client.dio.post('/api/auth/login', data: const {});

      expect(seen!.headers.containsKey('X-Shop-Id'), isFalse,
          reason: 'signing in happens before there is a shop to act for, and '
              'the auth routes span every shop server-side');
    });
  });
}
