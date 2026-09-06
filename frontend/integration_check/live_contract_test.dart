// Drives the REAL app code against a REAL running backend.
//
// NOT PART OF `flutter test`. It lives outside test/ deliberately: it needs a
// backend on the other end, and a suite that fails when nothing is listening
// is a suite people learn to ignore. Run it by hand:
//
//   flutter test integration_check \
//     --dart-define=API_BASE_URL=http://localhost:8088/v1
//
// WHAT IT IS FOR. Every other Flutter test in this repo answers the app's
// requests with JSON the test itself wrote, which proves the parsing matches
// what somebody BELIEVED the backend sends. This one asks the backend.
// The prints ARE the output of this check: it exists to report what a real
// backend actually said, and a lint that silences that would defeat it. Not
// used to hide anything - nothing here runs in the app.
// ignore_for_file: avoid_print

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';
import 'package:gpstore/core/config/app_environment.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';
import 'package:gpstore/core/storage/token_storage.dart';
import 'package:gpstore/features/auth/data/auth_repository.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';
import 'package:gpstore/features/admin/data/shop_self_service_repository.dart';
import 'package:gpstore/features/cart/data/cart_repository.dart';
import 'package:gpstore/features/worker/data/worker_repository.dart';

import '../test/support/test_api_client.dart';

void main() {
  late ApiClient api;
  late TokenStorage tokens;
  int? actingShopId;

  setUp(() {
    setUpFakeSecureStorage();
    // setUpFakeSecureStorage initialises the test binding, and the binding
    // installs a _MockHttpOverrides that answers EVERY HttpClient request
    // with a bodyless 400 so ordinary tests cannot reach the network. This
    // file is the one that is supposed to, so the guard comes off here and
    // nowhere else.
    HttpOverrides.global = null;
    tokens = TokenStorage();
    actingShopId = null;
    api = ApiClient(
      tokenStorage: tokens,
      activeShopId: () => actingShopId,
    );
  });

  test('the app is pointed at a real backend', () {
    expect(AppEnvironment.current.baseUrl, contains('localhost'),
        reason: 'pass --dart-define=API_BASE_URL=http://localhost:8088/v1');
  });

  group('marketplace discovery, parsed by the app', () {
    test('mode comes back as the app models it', () async {
      final mode = await MarketplaceRepository(apiClient: api).mode();
      expect(mode.mode, isNotEmpty);
      print('MODE: ${mode.mode} multiShop=${mode.multiShop}');
    });

    test('shops near a point parse into Storefronts', () async {
      final shops = await MarketplaceRepository(apiClient: api)
          .shopsNear(latitude: 27.16231, longitude: 83.940468);
      print('SHOPS NEAR: ${shops.length}');
      for (final shop in shops.take(3)) {
        expect(shop.shopId, greaterThan(0));
        expect(shop.displayName, isNotNull);
        print('  ${shop.shopId} ${shop.displayName} ${shop.distanceKm}km');
      }
    });

    test('a point nobody serves is an empty list, not an error', () async {
      final shops = await MarketplaceRepository(apiClient: api)
          .shopsNear(latitude: 0.0, longitude: 0.0);
      expect(shops, isEmpty);
    });
  });

  group('a real customer session', () {
    late String email;

    setUp(() async {
      final stamp = DateTime.now().microsecondsSinceEpoch;
      // The last nine digits, not the first: the leading digits of a
      // microsecond clock are identical for weeks, so two runs a second
      // apart would ask for the same phone number and the second would be
      // refused as a duplicate account.
      final digits = stamp.toString();
      email = 'live$stamp@example.test';
      await AuthRepository(apiClient: api, tokenStorage: tokens).register(
        name: 'Live check',
        email: email,
        phone: '9${digits.substring(digits.length - 9)}',
        password: 'LiveCheck!2345',
      );
    });

    test('register hands back a session the client can use', () async {
      expect(await tokens.getAccessToken(), isNotNull);
      final cart = await CartRepository(apiClient: api).getMyCart();
      expect(cart.items, isEmpty);
    });

    test('a basket names the shop each line came off', () async {
      final repo = CartRepository(apiClient: api);
      final cart = await repo.addToCart(variantId: 10, quantity: 1);

      expect(cart.items, hasLength(1));
      expect(cart.items.single.shopId, isNotNull,
          reason: 'every cart line carries the shop it was added from');
      print('CART LINE shopId=${cart.items.single.shopId} '
          'shops=${cart.shops.map((s) => "${s.shopId}:${s.shopName}").toList()}');
    });

    test('naming a shop the credential does not cover is refused, '
        'never honoured', () async {
      actingShopId = 999999;
      await expectLater(
        CartRepository(apiClient: api).getMyCart(),
        throwsA(isA<Object>()),
        reason: 'a client-supplied shop id may only NARROW (backend §78) - '
            'an unauthorised one must fail, not silently widen or switch',
      );
    });
  });

  // The staff accounts are created by the runner (see the Slice 14 report):
  // registered through the real /api/auth/register, then given a role and a
  // shop_staff row directly, because the API has no self-service route for
  // "make me a platform admin" and should not have one.
  group('staff sessions, against the real backend', () {
    const password = 'LiveCheck!2345';
    const stamp = String.fromEnvironment('LIVE_ACCOUNT_STAMP');

    Future<void> signIn(String prefix) =>
        AuthRepository(apiClient: api, tokenStorage: tokens)
            .login(email: '$prefix-$stamp@example.test', password: password);

    test('the platform admin reads the marketplace it runs', () async {
      if (stamp.isEmpty) return;
      await signIn('check-plat');
      final platform = PlatformRepository(apiClient: api);

      final merchants = await platform.merchants();
      print('MERCHANTS: ${merchants.length}');
      final shops = await platform.shops();
      print('PLATFORM SHOPS: ${shops.length}');
      final overview = await platform.overview();
      print('OVERVIEW shops=${overview.shops.length} '
          'orders=${overview.totals?.orderCount}');
      expect(shops, isNotEmpty);
    });

    test('a shop admin reads their own shop and nothing wider', () async {
      if (stamp.isEmpty) return;
      await signIn('check-merch');
      final shop = ShopSelfServiceRepository(apiClient: api);

      final readiness = await shop.readiness();
      print('READINESS shop=${readiness.shopId} '
          'canTakeOrders=${readiness.canTakeOrders} '
          'blockers=${readiness.outstandingBlockers.length}');

      final earnings = await shop.earnings();
      print('EARNINGS gross=${earnings.grossSales} refunds=${earnings.refunds}');
      expect(readiness.shopId, 1, reason: 'this staff member belongs to Shop #1');
    });

    test('a shop admin is refused the platform surface', () async {
      if (stamp.isEmpty) return;
      await signIn('check-merch');
      await expectLater(
        PlatformRepository(apiClient: api).merchants(),
        throwsA(isA<Object>()),
        reason: 'running the marketplace is not something a merchant may do',
      );
    });
  });

  group('a worker session, against the real backend', () {
    const stamp = String.fromEnvironment('LIVE_ACCOUNT_STAMP');

    Future<void> signInAsRider() async {
      // Exactly what worker_login_screen.dart posts. A worker credential is
      // not a customer credential and does not go through AuthRepository.
      final response = await api.dio.post(
        '/api/worker/auth/login',
        data: {
          'identifier': 'check-rider-$stamp@gmail.com',
          'password': 'LiveCheck!2345',
        },
      );
      await tokens.saveTokens(
        accessToken: response.data['accessToken'] as String,
        refreshToken: 'worker-sessions-have-none',
      );
    }

    test('the rider sees themselves, and their shop', () async {
      if (stamp.isEmpty) return;
      await signInAsRider();
      final me = await WorkerRepository(apiClient: api).me();
      print('WORKER ${me.name} status=${me.status} '
          'activeTasks=${me.activeTasks.length}');
      expect(me.name, isNotEmpty);
    });

    test('the rider can read their own round', () async {
      if (stamp.isEmpty) return;
      await signInAsRider();

      // TWO WAYS TO THE SAME ROWS, and the app takes the first.
      // /api/worker/me carries activeTasks, which the backend builds by
      // calling the very same getMyAssignments - so WorkerHomeScreen already
      // has the round without a second request. The bare endpoint is checked
      // beside it because it is still public API, and because it is the one
      // that used to answer "Sign in with a worker login" to a signed-in
      // worker (fixed in Slice 14).
      final me = await WorkerRepository(apiClient: api).me();
      print('ROUND via /worker/me: ${me.activeTasks.length} assignment(s)');

      final direct = await api.dio.get('/api/deliveries/my-assignments');
      print('ROUND via /deliveries/my-assignments: '
          '${(direct.data as List).length} assignment(s)');
      expect(direct.statusCode, 200);
      expect((direct.data as List).length, me.activeTasks.length,
          reason: 'the worker app and the delivery endpoint must not disagree '
              'about what this rider has to do');
    });

    test('a rider is refused the platform surface', () async {
      if (stamp.isEmpty) return;
      await signInAsRider();
      await expectLater(
        PlatformRepository(apiClient: api).merchants(),
        throwsA(isA<Object>()),
        reason: 'a delivery worker does not run the marketplace',
      );
    });
  });
}
