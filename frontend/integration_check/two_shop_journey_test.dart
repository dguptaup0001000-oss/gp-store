// THE REAL APP, AGAINST A REAL TWO-SHOP MARKETPLACE.
//
// The prints ARE the output: this file exists to report what a real backend
// said to the real provider graph, and nothing here runs in the app.
// ignore_for_file: avoid_print
//
//   tools/multishop/run_two_shop_verification.sh      # builds the fixture
//   flutter test integration_check/two_shop_journey_test.dart \
//     --dart-define=TWO_SHOP_FIXTURE=/tmp/two-shop/fixture.json
//
// WHY THIS EXISTS ALONGSIDE marketplace_wiring_test. That file proves the
// provider graph reaches a backend. It cannot prove ISOLATION, because it
// runs against a deployment with one real shelf - a filter that narrows to
// "the caller's shop" looks perfect when there is only one shop to narrow to.
// This one runs against the two-shop marketplace the verification script
// builds from an empty database: two merchants, two shelves, two riders, and
// products only one of them sells.
//
// NOTHING IS MOCKED AND NOTHING IS SEEDED FOR THE APP'S BENEFIT. Every id
// below was created minutes earlier through the same public API a real
// onboarding uses; this file reads them out of the fixture the script wrote,
// because the database is rebuilt on every run and ids typed into a Dart file
// would be fiction by the second run.
import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/shop_context.dart';
import 'package:gpstore/core/store/store_status.dart';
import 'package:gpstore/core/store/store_status_provider.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/admin/presentation/shop_self_service_providers.dart';
import 'package:gpstore/features/auth/presentation/auth_providers.dart';
import 'package:gpstore/features/cart/presentation/cart_providers.dart';
import 'package:gpstore/features/checkout/data/checkout_repository.dart';
import 'package:gpstore/features/orders/presentation/order_group_providers.dart';
import 'package:gpstore/features/orders/presentation/orders_providers.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/product_feed_provider.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';
import 'package:gpstore/features/worker/data/worker_repository.dart';

import '../test/support/test_api_client.dart';

late final Map<String, dynamic> fixture;

int get shopA => fixture['shopA'] as int;
int get shopB => fixture['shopB'] as int;
String get password => fixture['password'] as String;
String get stamp => fixture['stamp'] as String;

/// A container wired exactly as the app wires itself, minus the store-status
/// poll (a real Dio stream under the test binding leaves a pending timer).
ProviderContainer appContainer() => ProviderContainer(overrides: [
      storeStatusProvider
          .overrideWith((ref) => Stream.value(StoreStatus.unknown())),
    ]);

void main() {
  setUpAll(() {
    const path = String.fromEnvironment('TWO_SHOP_FIXTURE',
        defaultValue: '/tmp/two-shop/fixture.json');
    final file = File(path);
    if (!file.existsSync()) {
      throw StateError(
          'No two-shop fixture at $path. Run tools/multishop/'
          'run_two_shop_verification.sh first - this check has nothing to '
          'talk to without it, and inventing one would defeat the point.');
    }
    fixture = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
    print('FIXTURE: shops $shopA and $shopB at ${fixture['baseUrl']}');
  });

  setUp(() {
    setUpFakeSecureStorage();
    // The binding installs HttpOverrides that answer every request with a
    // bodyless 400. This file is supposed to reach the network.
    HttpOverrides.global = null;
  });

  group('THE CUSTOMER JOURNEY, across two kiranas', () {
    late ProviderContainer app;
    late int addressId;

    setUp(() async {
      app = appContainer();
      final digits = DateTime.now().microsecondsSinceEpoch.toString();
      await app.read(authControllerProvider.notifier).register(
            name: 'Two-shop shopper',
            email: 'twoshop$digits@example.test',
            phone: '9${digits.substring(digits.length - 9)}',
            password: password,
          );
      addressId = await _saveAddress(app);
    });

    tearDown(() => app.dispose());

    test('registration -> address -> BOTH shops are discovered', () async {
      final pin = app.read(deliveryPinProvider);
      expect(pin, isNotNull, reason: 'the picker asks with the default address');

      final shops = await app.read(shopsNearProvider(pin!).future);
      final ids = shops.map((s) => s.shopId).toSet();
      print('DISCOVERY: ${shops.length} shop(s) -> $ids');
      expect(ids, containsAll([shopA, shopB]),
            reason: 'a marketplace that offers one shop is a shop');

      // AND THE STOREFRONT SAYS WHETHER EACH IS OPEN, which is the field the
      // app needs to draw a shut kirana honestly.
      final response = await app
          .read(apiClientProvider)
          .dio
          .get('/api/marketplace/shops',
              queryParameters: {'lat': fixture['lat'], 'lng': fixture['lng']});
      for (final row in response.data as List) {
        print('STOREFRONT ${row['shopId']}: openNow=${row['openNow']} '
            'accepting=${row['acceptingOrders']} '
            'deliversHere=${row['deliversHere']}');
        expect(row['openNow'], isNotNull);
        expect(row['acceptingOrders'], isNotNull);
      }
    });

    test('opening Shop A shows A\'s shelf, and opening Shop B shows B\'s',
        () async {
      final onA = await _shelfSeenIn(app, shopA);
      final onB = await _shelfSeenIn(app, shopB);

      print('SHOP A FEED: ${onA.length} product(s)');
      print('SHOP B FEED: ${onB.length} product(s)');

      expect(onA, contains(fixture['exclusiveAProduct']),
          reason: "Shop A must show what Shop A lists");
      expect(onA, isNot(contains(fixture['exclusiveBProduct'])),
          reason: "SHOP A'S FLUTTER UI IS SHOWING SHOP B'S INVENTORY");
      expect(onB, contains(fixture['exclusiveBProduct']));
      expect(onB, isNot(contains(fixture['exclusiveAProduct'])),
          reason: "SHOP B'S FLUTTER UI IS SHOWING SHOP A'S INVENTORY");
    });

    test('every other screen in the app agrees with the feed', () async {
      // ONE SURFACE AT A TIME, because the app reaches the catalogue by more
      // than one route and a customer who searches is not asking the feed.
      await _openShop(app, shopA);

      final searched = await app
          .read(productsRepositoryProvider)
          .searchInstant(stamp, size: 50);
      final searchedIds = searched.map((p) => p.id).toSet();
      print('SEARCH in A: $searchedIds');
      expect(searchedIds, isNot(contains(fixture['exclusiveBProduct'])),
          reason: 'search is the easiest door into another shop\'s stock');

      final brands = await app.read(brandsProvider.future);
      final brandNames = brands.map((b) => b.brand).toSet();
      print('BRANDS in A: ${brandNames.where((b) => b.contains(stamp))}');
      expect(brandNames, contains(fixture['brandA']));
      expect(brandNames, isNot(contains(fixture['brandB'])),
          reason: 'a brand tile for a brand this shop never stocked opens on '
              'an empty grid');

      final arrivals = await app.read(newArrivalsProvider.future);
      expect(arrivals.map((p) => p.id), isNot(contains(fixture['exclusiveBProduct'])),
          reason: 'New Arrivals is a browse surface like any other');

      final preview = await app
          .read(categoryPreviewProvider(fixture['categoryId'] as int).future);
      print('CATEGORY PREVIEW in A: ${preview.map((p) => p.id).toList()}');
      expect(preview.map((p) => p.id), isNot(contains(fixture['exclusiveBProduct'])));

      // THE PRODUCT PAGE, reached by id - a deep link, a shared card, or a
      // home screen left open from before the customer switched shops.
      final mine = await app
          .read(productDetailProvider(fixture['exclusiveAProduct'] as int).future);
      expect(mine.id, fixture['exclusiveAProduct']);
      await expectLater(
        app.read(productDetailProvider(fixture['exclusiveBProduct'] as int).future),
        throwsA(isA<Object>()),
        reason: "A DEEP LINK MUST NOT OPEN ANOTHER SHOP'S PRODUCT PAGE",
      );
      print('PRODUCT PAGE: own opens, other refused');
    });

    test('one basket from two shops becomes two shop orders', () async {
      await _openShop(app, shopA);
      await app.read(cartRepositoryProvider).addToCart(
          variantId: fixture['exclusiveAVariant'] as int, quantity: 1);

      await _openShop(app, shopB);
      final cart = await app.read(cartRepositoryProvider).addToCart(
          variantId: fixture['exclusiveBVariant'] as int, quantity: 1);

      final shopsInBasket = cart.items.map((i) => i.shopId).toSet();
      print('BASKET: ${cart.items.length} line(s) from $shopsInBasket, '
          'shop refs ${cart.shops.map((s) => s.shopName).toList()}');
      expect(shopsInBasket, containsAll([shopA, shopB]),
          reason: 'ONE BASKET, TWO KIRANAS - the whole point of §16');

      final placed =
          await CheckoutRepository(apiClient: app.read(apiClientProvider))
              .placeOrder(
        addressId: addressId,
        paymentMethod: 'COD',
        idempotencyKey: 'two-shop-${DateTime.now().microsecondsSinceEpoch}',
      );
      print('CHECKOUT: group=${placed.orderGroupId} '
          'shopOrders=${placed.shopOrders.length}');
      expect(placed.success, isTrue);
      expect(placed.shopOrders.length, 2,
          reason: 'a basket across two shops is TWO orders, one per shop - '
              'each kirana packs and is paid for its own');

      // HISTORY, through the provider the screen watches.
      app.invalidate(myOrdersProvider);
      final history = await app.read(myOrdersProvider.future);
      final names = history.orders.map((o) => o.shopName).toSet();
      print('HISTORY: ${history.orders.length} order(s), shops $names');
      expect(history.orders.length, greaterThanOrEqualTo(2));
      expect(names.where((n) => n != null).length, greaterThanOrEqualTo(2),
          reason: 'the customer has to be told which kirana each order is from');

      final group =
          await app.read(orderGroupProvider(placed.orderGroupId!).future);
      print('GROUP ${group.groupNumber}: shops=${group.shopCount} '
          'names=${group.shopOrders.map((o) => o.shopName).toList()}');
      expect(group.shopOrders.length, 2);
    });

    test('changing Shop A\'s price never moves what Shop B\'s UI shows',
        () async {
      // THE SHARED LINE. Both shops list this catalogue item, at their own
      // prices - which is what makes this a real question rather than a
      // trivially true one.
      final sharedProduct = fixture['sharedProduct'] as int;
      final sharedVariant = fixture['sharedVariant'] as int;

      final beforeA = await _priceSeenIn(app, shopA, sharedProduct);
      final beforeB = await _priceSeenIn(app, shopB, sharedProduct);
      print('BEFORE: A shows $beforeA, B shows $beforeB');
      expect(beforeA, isNotNull);
      expect(beforeB, isNotNull);

      // The shopkeeper of A changes A's price, through the merchant API a
      // real shopkeeper uses. Nothing touches Shop B.
      final merchant = appContainer();
      addTearDown(merchant.dispose);
      await merchant.read(authControllerProvider.notifier).login(
          email: fixture['ownerA'] as String, password: password);
      final moved = (beforeA! + 13).roundToDouble();
      await merchant.read(apiClientProvider).dio.put(
        '/api/shop/listings/$sharedVariant',
        data: {
          'sellingPrice': moved,
          'costPrice': moved * 0.7,
          'mrp': moved * 1.4,
          'available': true,
          'active': true,
        },
      );
      print('SHOP A REPRICED to $moved by ${fixture['ownerA']}');

      final afterA = await _priceSeenIn(app, shopA, sharedProduct);
      final afterB = await _priceSeenIn(app, shopB, sharedProduct);
      print('AFTER:  A shows $afterA, B shows $afterB');

      expect(afterA, moved,
          reason: "the shopkeeper's own change has to reach their own "
              'storefront, or the price screen is decoration');
      expect(afterB, beforeB,
          reason: "ONE KIRANA'S PRICE CHANGE MOVED THE OTHER KIRANA'S "
              'SHELF. §103: local businesses remain independent - what a '
              'shop charges is nobody else\'s to change, and the two shops '
              'are pointing at the same catalogue row.');
    });
  });

  group('THE SHOPKEEPERS, one for each kirana', () {
    test('each merchant\'s dashboard is their own shop and their own orders',
        () async {
      for (final owner in [
        (email: fixture['ownerA'] as String, shopId: shopA),
        (email: fixture['ownerB'] as String, shopId: shopB),
      ]) {
        final app = appContainer();
        addTearDown(app.dispose);
        await app
            .read(authControllerProvider.notifier)
            .login(email: owner.email, password: password);

        final profile = await app.read(myShopProfileProvider.future);
        final earnings = await app.read(myShopEarningsProvider(30).future);
        final orders = await app
            .read(apiClientProvider)
            .dio
            .get('/api/orders/admin/all', queryParameters: {'page': 0, 'size': 50});
        final content = (orders.data as Map)['content'] as List;
        final shopIds = content.map((o) => o['shopId']).toSet();

        print('MERCHANT ${owner.email}: shop=${profile.id} '
            '"${profile.displayName}" orders=${content.length} from $shopIds '
            'gross=${earnings.grossSales}');

        expect(profile.id, owner.shopId,
            reason: 'the shop comes off the credential, never off a request');
        expect(shopIds.where((id) => id != owner.shopId), isEmpty,
            reason: "A SHOPKEEPER'S ORDER LIST HELD ANOTHER SHOP'S ORDERS");
      }
    });
  });

  group('THE RIDERS, one for each kirana', () {
    test('each rider\'s round holds only their own shop\'s deliveries',
        () async {
      for (final rider in [
        (
          email: fixture['riderA'] as String,
          ownOrder: fixture['orderA'],
          otherOrder: fixture['orderB']
        ),
        (
          email: fixture['riderB'] as String,
          ownOrder: fixture['orderB'],
          otherOrder: fixture['orderA']
        ),
      ]) {
        final app = appContainer();
        addTearDown(app.dispose);
        final response =
            await app.read(apiClientProvider).dio.post('/api/worker/auth/login',
                data: {'identifier': rider.email, 'password': password});
        await app.read(tokenStorageProvider).saveTokens(
              accessToken: (response.data as Map)['accessToken'] as String,
              refreshToken: 'worker-sessions-have-none',
            );

        final me =
            await WorkerRepository(apiClient: app.read(apiClientProvider)).me();
        final round =
            await app.read(apiClientProvider).dio.get('/api/deliveries/my-assignments');
        // BY ORDER, NOT BY SHOP ID. The delivery payload does not carry a
        // shop id - deliberately: a rider has no use for one, and a field
        // that exists only for a test is a field somebody will start
        // trusting. The order each shop packed is known from the fixture, so
        // "whose round is this" is answerable without adding anything.
        final orderIds = (round.data as List)
            .map((d) => (d as Map)['orderId'])
            .where((id) => id != null)
            .toSet();

        print('RIDER ${rider.email}: ${me.name} '
            'tasks=${me.activeTasks.length} deliveries=${(round.data as List).length} '
            'orders=$orderIds');
        expect(orderIds, isNotEmpty,
            reason: 'a rider with no round proves nothing either way');
        expect(orderIds, contains(rider.ownOrder),
            reason: "the rider must have their own shop's delivery");
        expect(orderIds, isNot(contains(rider.otherOrder)),
            reason: "A RIDER'S ROUND HELD ANOTHER SHOP'S DELIVERY (W4: riders "
                'are per shop, there is no shared pool)');
      }
    });
  });
}

// ------------------------------------------------------------------ helpers

Future<int> _saveAddress(ProviderContainer app) async {
  final address = await app.read(addressRepositoryProvider).createAddress(
        AddressModel(
          fullName: 'Two-shop shopper',
          mobileNumber: '9000000017',
          houseNo: '1',
          area: 'Two Shop Area',
          city: 'Two Shop City',
          state: 'UP',
          pincode: '273001',
          latitude: (fixture['lat'] as num).toDouble(),
          longitude: (fixture['lng'] as num).toDouble(),
          defaultAddress: true,
        ),
      );
  app.invalidate(myAddressesProvider);
  await app.read(myAddressesProvider.future);
  return address.id!;
}

/// Opens a storefront the way the picker screen does.
///
/// shopSwitchProvider is what _ShopTile calls, and it does two things that
/// matter here: it puts the id on X-Shop-Id for every following request, and
/// it throws away the last shop's cached answers. Reading the providers
/// without going through it would be testing an app nobody ships.
Future<void> _openShop(ProviderContainer app, int shopId) async {
  app.read(shopSwitchProvider).select(shopId);
  expect(app.read(shopContextProvider), shopId);
}

/// Which products this shop's home feed actually puts on the screen.
Future<Set<int>> _shelfSeenIn(ProviderContainer app, int shopId) async {
  await _openShop(app, shopId);
  final feed = await app.read(productFeedProvider.future);
  return feed.products.map((p) => p.id).toSet();
}

/// The price this shop's product page shows for one product.
Future<double?> _priceSeenIn(
    ProviderContainer app, int shopId, int productId) async {
  await _openShop(app, shopId);
  final product = await app.read(productDetailProvider(productId).future);
  final ProductVariant? variant = product.primaryVariant;
  return variant?.sellingPrice;
}
