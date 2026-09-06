// The Slice 15 wiring, driven against a REAL running backend.
//
// The prints ARE the output: this file exists to report what a real backend
// said, and to prove that a real user action - not a repository call in a
// test - reaches it. Nothing here runs in the app.
// ignore_for_file: avoid_print
//
//   flutter test integration_check/marketplace_wiring_test.dart \
//     --dart-define=API_BASE_URL=http://localhost:8088/v1 \
//     --dart-define=LIVE_ACCOUNT_STAMP=<stamp>
//
// WHY A ProviderContainer AND NOT BARE REPOSITORIES. Slice 14 proved the
// repositories speak the right protocol. The question this slice raises is
// different and is the whole of D3: does the PROVIDER GRAPH the screens
// actually watch reach the backend? So everything below goes through the
// real providers - the same objects the widgets read - and a shop is chosen
// through shopSwitchProvider, which is what the picker screen calls.
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/shop_context.dart';
import 'package:gpstore/core/store/store_status.dart';
import 'package:gpstore/core/store/store_status_provider.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/admin/presentation/platform_providers.dart';
import 'package:gpstore/features/admin/presentation/shop_self_service_providers.dart';
import 'package:gpstore/features/auth/presentation/auth_providers.dart';
import 'package:gpstore/features/cart/presentation/cart_providers.dart';
import 'package:gpstore/features/checkout/data/checkout_repository.dart';
import 'package:gpstore/features/orders/presentation/order_group_providers.dart';
import 'package:gpstore/features/orders/presentation/orders_providers.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';
import 'package:gpstore/features/worker/data/worker_repository.dart';

import '../test/support/test_api_client.dart';

const stamp = String.fromEnvironment('LIVE_ACCOUNT_STAMP');
const password = 'LiveCheck!2345';

/// A container wired exactly as the app wires itself, minus the store-status
/// poll (a real Dio stream under the test binding leaves a pending timer).
ProviderContainer appContainer() => ProviderContainer(overrides: [
      storeStatusProvider
          .overrideWith((ref) => Stream.value(StoreStatus.unknown())),
    ]);

void main() {
  setUp(() {
    setUpFakeSecureStorage();
    // The binding installs HttpOverrides that answer every request with a
    // bodyless 400. This file is the one that is supposed to reach the
    // network, so the guard comes off here and nowhere else.
    HttpOverrides.global = null;
  });

  group('CUSTOMER: the journey the picker screen drives', () {
    late ProviderContainer app;
    late int variantId;

    setUp(() async {
      app = appContainer();
      final digits = DateTime.now().microsecondsSinceEpoch.toString();
      await app.read(authControllerProvider.notifier).register(
            name: 'S15 shopper',
            email: 'wire$digits@example.test',
            phone: '9${digits.substring(digits.length - 9)}',
            password: password,
          );
      // Passed in by the runner from a variant that actually has stock right
      // now. Hardcoding one meant the checkout test started failing the
      // moment earlier runs had bought the shelf out - a fixture problem
      // wearing the costume of a defect.
      variantId = const int.fromEnvironment('LIVE_VARIANT_ID', defaultValue: 10);
    });

    tearDown(() => app.dispose());

    test('1-2. a signed-in customer discovers the shops that serve an address',
        () async {
      // The address is what makes discovery answerable at all - the backend
      // fails closed on a missing pin.
      final address = await app.read(addressRepositoryProvider).createAddress(
            const AddressModel(
              fullName: 'S15 shopper',
              mobileNumber: '9000000015',
              houseNo: '1',
              area: 'Wiring Area',
              city: 'Wiring City',
              state: 'UP',
              pincode: '273001',
              latitude: 27.16231,
              longitude: 83.940468,
              defaultAddress: true,
            ),
          );
      expect(address.id, isNotNull);
      app.invalidate(myAddressesProvider);
      await app.read(myAddressesProvider.future);

      final pin = app.read(deliveryPinProvider);
      expect(pin, isNotNull, reason: 'the picker asks with the default address');

      final shops = await app.read(shopsNearProvider(pin!).future);
      print('DISCOVERY: ${shops.length} shop(s) serve this address');
      expect(shops, isNotEmpty);
    });

    test('3. selecting a shop puts X-Shop-Id on the very next request',
        () async {
      final pin = await _pinFor(app);
      final shops = await app.read(shopsNearProvider(pin).future);
      final chosen = shops.first;

      // THE REAL SELECTION PATH: exactly what _ShopTile calls.
      app.read(shopSwitchProvider).select(chosen.shopId);
      expect(app.read(shopContextProvider), chosen.shopId);

      final sent = <String, String>{};
      final dio = app.read(apiClientProvider).dio;
      dio.interceptors.add(InterceptorsWrapperRecording(sent));
      await dio.get('/api/categories');

      print('SELECTED shop=${chosen.shopId} header=${sent[shopHeaderName]}');
      expect(sent[shopHeaderName], '${chosen.shopId}',
          reason: 'the choice must reach the server, or it is decoration');
    });

    test('4-5. the chosen shop answers with its own catalogue and takes an '
        'item into the basket', () async {
      final pin = await _pinFor(app);
      final shops = await app.read(shopsNearProvider(pin).future);
      app.read(shopSwitchProvider).select(_shopWithTheFixtureCatalogue(shops));

      final categories = await app.read(categoriesProvider.future);
      print('CATALOGUE: ${categories.length} categories under the chosen shop');

      final cart = await app
          .read(cartRepositoryProvider)
          .addToCart(variantId: variantId, quantity: 1);
      expect(cart.items, isNotEmpty);
      print('CART: line shop=${cart.items.first.shopId} '
          'shops=${cart.shops.length}');
    });

    test('7-10. checkout creates a group, and history can open it', () async {
      final pin = await _pinFor(app);
      final addresses = await app.read(myAddressesProvider.future);
      final shops = await app.read(shopsNearProvider(pin).future);
      app.read(shopSwitchProvider).select(_shopWithTheFixtureCatalogue(shops));

      await app
          .read(cartRepositoryProvider)
          .addToCart(variantId: variantId, quantity: 1);

      final placed = await CheckoutRepository(apiClient: app.read(apiClientProvider))
          .placeOrder(
        addressId: addresses.first.id!,
        paymentMethod: 'COD',
        // REQUIRED BY THE BACKEND, and the app sends one per attempt. A
        // fresh key here is a fresh checkout, which is what this is.
        idempotencyKey:
            'wire-${DateTime.now().microsecondsSinceEpoch}',
      );
      print('ORDER: id=${placed.orderId} group=${placed.orderGroupId} '
          'shopOrders=${placed.shopOrders.length}');
      expect(placed.success, isTrue);
      expect(placed.orderGroupId, isNotNull,
          reason: 'every checkout is a group, even a one-shop one');
      expect(placed.shopOrders, isNotEmpty);

      // ORDER HISTORY, through the provider the screen watches.
      app.invalidate(myOrdersProvider);
      final history = await app.read(myOrdersProvider.future);
      expect(history.orders, isNotEmpty);
      print('HISTORY: ${history.orders.length} order(s), '
          'first shopName=${history.orders.first.shopName}');
      expect(history.orders.first.shopName, isNotNull,
          reason: 'OrderSummary.shopName is parsed AND now rendered');

      // THE GROUP SCREEN'S OWN PROVIDER.
      final group =
          await app.read(orderGroupProvider(placed.orderGroupId!).future);
      print('GROUP: ${group.groupNumber} shops=${group.shopCount} '
          'names=${group.shopOrders.map((o) => o.shopName).toList()}');
      expect(group.shopOrders, isNotEmpty);
      expect(group.shopOrders.first.shopName, isNotNull,
          reason: 'a group screen without shop names is a list of numbers');

      // AND THE LIST THE HISTORY SCREEN USES TO SPOT MULTI-SHOP CHECKOUTS.
      final checkouts = await app.read(myCheckoutsProvider.future);
      expect(checkouts, isNotEmpty);
      print('CHECKOUTS: ${checkouts.length}');
    });
  });

  group('MERCHANT: the screens a shopkeeper opens', () {
    late ProviderContainer app;

    setUp(() async {
      app = appContainer();
      await app.read(authControllerProvider.notifier).login(
            email: 'check-merch-$stamp@example.test',
            password: password,
          );
    });

    tearDown(() => app.dispose());

    test('12-17. dashboard facts, orders, earnings and readiness all answer',
        () async {
      final profile = await app.read(myShopProfileProvider.future);
      final readiness = await app.read(myShopReadinessProvider.future);
      final openWork = await app.read(myShopOpenWorkProvider.future);
      final earnings = await app.read(myShopEarningsProvider(30).future);

      print('MY SHOP: ${profile.displayName} status=${profile.status}');
      print('READINESS: canTakeOrders=${readiness.canTakeOrders} '
          'blockers=${readiness.outstandingBlockers.length} '
          'advice=${readiness.outstandingAdvice.length}');
      print('OPEN WORK: $openWork');
      print('EARNINGS: gross=${earnings.grossSales} net=${earnings.netSales} '
          'awaiting=${earnings.awaitingCollection} orders=${earnings.orderCount}');

      expect(profile.id, 1, reason: 'this staff member belongs to Shop #1');
      expect(earnings.orderCount, greaterThan(0));
    });

    test('a merchant cannot open the marketplace console', () async {
      await expectLater(
        app.read(platformMerchantsProvider.future),
        throwsA(isA<Object>()),
        reason: 'running the marketplace is not something a shop may do',
      );
    });
  });

  group('PLATFORM ADMIN: the console, including the writes', () {
    late ProviderContainer app;

    setUp(() async {
      app = appContainer();
      await app.read(authControllerProvider.notifier).login(
            email: 'check-plat-$stamp@example.test',
            password: password,
          );
    });

    tearDown(() => app.dispose());

    test('21-24. merchants, shops and the market overview', () async {
      final merchants = await app.read(platformMerchantsProvider.future);
      final shops = await app.read(platformShopsProvider.future);
      final overview = await app.read(marketOverviewProvider(30).future);

      print('MERCHANTS: ${merchants.length}');
      print('SHOPS: ${shops.length}');
      print('OVERVIEW: orders=${overview.totals?.orderCount} '
          'shops=${overview.shops.length} merchants=${overview.merchantCount}');
      expect(merchants, isNotEmpty);
      expect(shops, isNotEmpty);
    });

    test('approve and restrict, on a merchant and shop made for the purpose',
        () async {
      // NOTHING EXISTING IS TOUCHED. §10: no silent mutation of a shared
      // database. This registers its own merchant through the same API the
      // console uses, moves it, and leaves it REMOVED.
      final api = app.read(apiClientProvider);
      final created = await api.dio.post('/api/platform/merchants', data: {
        'legalName':
            'Wiring test merchant $stamp-${DateTime.now().microsecondsSinceEpoch}',
        'displayName': 'Wiring test $stamp',
        'contactPhone': '9000000000',
        'contactEmail': 'wiring-$stamp@example.test',
      });
      final merchantId = (created.data as Map)['id'] as int;
      print('CREATED merchant $merchantId');

      final repository = app.read(platformRepositoryProvider);

      // THE LADDER IS THE BACKEND'S AND IT IS ENFORCED. A merchant cannot
      // jump from APPLICATION to APPROVED - MerchantLifecycleService refuses
      // it and names what is allowed - so the console walks the same steps a
      // reviewer would.
      final underReview = await repository.setMerchantStatus(
          merchantId: merchantId,
          status: 'PENDING_REVIEW',
          reason: 'documents received');
      print('SEND FOR REVIEW -> ${underReview.status}');

      final approved = await repository.setMerchantStatus(
          merchantId: merchantId, status: 'APPROVED', reason: 'papers checked');
      print('APPROVE MERCHANT -> ${approved.status}');
      expect(approved.status, 'APPROVED');

      final trading = await repository.setMerchantStatus(
          merchantId: merchantId, status: 'ACTIVE', reason: 'open for trade');
      expect(trading.status, 'ACTIVE');

      final shopCreated = await api.dio.post('/api/platform/shops', data: {
        'merchantId': merchantId,
        'code': 'WIRE-$stamp-${DateTime.now().microsecondsSinceEpoch}',
        'displayName': 'Wiring test shop',
      });
      final shopId = (shopCreated.data as Map)['id'] as int;
      print('CREATED shop $shopId');

      final open = await repository.setShopStatus(
          shopId: shopId, status: 'ACTIVE', reason: 'ready to trade');
      print('APPROVE SHOP -> ${open.status}');
      expect(open.status, 'ACTIVE');

      final suspendedShop = await repository.setShopStatus(
          shopId: shopId, status: 'SUSPENDED', reason: 'wiring test');
      print('RESTRICT SHOP -> ${suspendedShop.status}');
      expect(suspendedShop.status, 'SUSPENDED');

      final suspendedMerchant = await repository.setMerchantStatus(
          merchantId: merchantId, status: 'SUSPENDED', reason: 'wiring test');
      print('RESTRICT MERCHANT -> ${suspendedMerchant.status}');
      expect(suspendedMerchant.status, 'SUSPENDED');

      // A SUSPENDED SHOP IS ABSENT FROM THE MARKETPLACE, not present with an
      // explanation - which is the customer-facing half of the same decision.
      await expectLater(
        app.read(marketplaceRepositoryProvider).storefront(shopId),
        throwsA(isA<Object>()),
      );

      // Left removed, so the shared database is as close to how it was found
      // as the lifecycle allows.
      final removed = await repository.setMerchantStatus(
          merchantId: merchantId, status: 'REMOVED', reason: 'wiring test done');
      expect(removed.status, 'REMOVED');
      print('CLEANED UP merchant $merchantId');
    });
  });

  group('WORKER: what the rider app reaches', () {
    late ProviderContainer app;

    setUp(() async {
      app = appContainer();
      final response = await app.read(apiClientProvider).dio.post(
        '/api/worker/auth/login',
        data: {
          'identifier': 'check-rider-$stamp@gmail.com',
          'password': password,
        },
      );
      await app.read(tokenStorageProvider).saveTokens(
            accessToken: (response.data as Map)['accessToken'] as String,
            refreshToken: 'worker-sessions-have-none',
          );
    });

    tearDown(() => app.dispose());

    test('18-20. the rider sees themselves and their round', () async {
      final me =
          await WorkerRepository(apiClient: app.read(apiClientProvider)).me();
      print('WORKER: ${me.name} status=${me.status} '
          'activeTasks=${me.activeTasks.length}');
      expect(me.name, isNotEmpty);

      final direct =
          await app.read(apiClientProvider).dio.get('/api/deliveries/my-assignments');
      expect(direct.statusCode, 200);
      expect((direct.data as List).length, me.activeTasks.length);
    });
  });

  group('SECURITY: manipulated ids still bounce', () {
    late ProviderContainer app;

    setUp(() async {
      app = appContainer();
      await app.read(authControllerProvider.notifier).login(
            email: 'check-merch-$stamp@example.test',
            password: password,
          );
    });

    tearDown(() => app.dispose());

    test('a shop admin naming a shop they do not staff is refused, not obeyed',
        () async {
      // §78: a client shop id may only NARROW. This one widens, so it must be
      // refused rather than honoured - and the refusal must not be silent.
      app.read(shopSwitchProvider).select(999999);
      await expectLater(
        app.read(apiClientProvider).dio.get('/api/shop/profile'),
        throwsA(isA<Object>()),
      );
      print('CROSS-SHOP HEADER: refused');
    });

    test("another shop's order, inventory and earnings are not readable",
        () async {
      final api = app.read(apiClientProvider);
      // Shop #1 staff, reaching for rows that belong to somebody else. The id
      // is the only thing changed; the credential is real.
      for (final path in [
        '/api/orders/2147483600',
        '/api/admin/inventory/2147483600',
        '/api/platform/merchants',
      ]) {
        var status = 0;
        try {
          final response = await api.dio.get(path);
          status = response.statusCode ?? 0;
        } catch (error) {
          status = -1;
        }
        print('DENIED $path -> ${status == -1 ? 'refused' : status}');
        expect(status, isNot(200), reason: '$path must not answer with data');
      }
    });
  });
}

/// Records the headers the client actually put on the wire.
class InterceptorsWrapperRecording extends Interceptor {
  InterceptorsWrapperRecording(this.seen);

  final Map<String, String> seen;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    options.headers.forEach((key, value) => seen[key] = '$value');
    handler.next(options);
  }
}

/// Which of the shops on offer this check buys from.
///
/// SHOP #1, WHEN IT IS THERE. Discovery answers with every shop that serves
/// the address, nearest first, and several fixture shops sit on the same
/// coordinates - so "the nearest" is whichever happens to sort first and may
/// have no catalogue at all. That is correct marketplace behaviour and a
/// useless basis for a stock fixture, so this names the shop the seeded
/// catalogue belongs to and falls back to the nearest if it is not offered.
int _shopWithTheFixtureCatalogue(List<dynamic> shops) {
  for (final shop in shops) {
    if (shop.shopId == 1) return 1;
  }
  return shops.first.shopId as int;
}

Future<({double lat, double lng})> _pinFor(ProviderContainer app) async {
  await app.read(addressRepositoryProvider).createAddress(
        const AddressModel(
          fullName: 'S15 shopper',
          mobileNumber: '9000000015',
          houseNo: '1',
          area: 'Wiring Area',
          city: 'Wiring City',
          state: 'UP',
          pincode: '273001',
          latitude: 27.16231,
          longitude: 83.940468,
          defaultAddress: true,
        ),
      );
  app.invalidate(myAddressesProvider);
  await app.read(myAddressesProvider.future);
  return app.read(deliveryPinProvider)!;
}
