import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';
import 'package:gpstore/core/store/store_status.dart';
import 'package:gpstore/core/store/store_status_provider.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/auth/presentation/auth_providers.dart';
import 'package:gpstore/features/auth/data/auth_repository.dart';
import 'package:gpstore/features/home/presentation/home_screen.dart';
import 'package:gpstore/features/marketplace/domain/marketplace_feed_models.dart';
import 'package:gpstore/features/products/data/products_repository.dart';
import 'package:gpstore/features/products/domain/brand_models.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';
import 'package:gpstore/features/wishlist/domain/wishlist_models.dart';
import 'package:gpstore/features/wishlist/presentation/wishlist_providers.dart';

import '../../../support/test_api_client.dart';

/// Records every call the home screen makes, and lets the above-the-fold
/// wave be held open so the gate can be observed while it is still shut.
class RecordingRepository implements ProductsRepository {
  final List<String> calls = [];
  List<Product> newArrivalProducts = const [];

  final categories = Completer<List<Category>>();
  final brands = Completer<List<BrandSummary>>();
  final offers = Completer<List<Coupon>>();

  @override
  Future<List<Category>> getCategories() {
    calls.add('categories');
    return categories.future;
  }

  @override
  Future<List<BrandSummary>> getBrands() {
    calls.add('brands');
    return brands.future;
  }

  @override
  Future<List<Coupon>> getActiveOffers() {
    calls.add('offers');
    return offers.future;
  }

  @override
  Future<List<Product>> getNewArrivals({int page = 0, int size = 10}) async {
    calls.add('new-arrivals');
    return newArrivalProducts;
  }

  @override
  Future<List<Product>> getTrending({int days = 7, int limit = 10}) async {
    calls.add('trending');
    return const [];
  }

  @override
  Future<List<Product>> getRecommendedForMe({int limit = 10}) async {
    calls.add('for-me');
    return const [];
  }

  @override
  Future<ProductPage> fetchFeed({int page = 0, int size = 20, int? shopId}) async {
    calls.add('feed');
    return const ProductPage(products: [], page: 0, hasNext: false, totalElements: 0);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class RecordingMarketplaceRepository implements MarketplaceRepository {
  final List<String> calls = [];
  final Map<CommerceMode, List<MarketplaceCard>> homeCards = {};

  @override
  Future<List<MarketplaceCard>> feed({
    required double? latitude,
    required double? longitude,
    CommerceMode? mode,
    int? categoryId,
    int? shopId,
    int page = 0,
    int size = 20,
  }) async {
    calls.add('marketplace-feed:$latitude:$longitude:${mode?.wire ?? 'ALL'}:${shopId ?? 'ALL'}:$page:$size');
    return homeCards[mode] ?? const [];
  }

  @override
  Future<List<MarketCategory>> categoriesNear({
    required double latitude,
    required double longitude,
  }) async => const [];

  @override
  Future<List<Storefront>> shopsNear({
    double? latitude,
    double? longitude,
  }) async => const [];

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class TestAuthRepository extends AuthRepository {
  TestAuthRepository({required super.apiClient, required super.tokenStorage,
    required this.authenticated});

  final bool authenticated;

  @override
  Future<bool> hasStoredSession() async => authenticated;

  @override
  Future<bool> shouldRestoreSession() async => true;
}

class TestWishlistController extends WishlistController {
  @override
  Future<List<WishlistItem>> build() async => const [];
}

void main() {
  setUpAll(setUpFakeSecureStorage);

  late RecordingRepository repository;
  late RecordingMarketplaceRepository marketplaceRepository;

  Future<void> openHome(
    WidgetTester tester, {
    bool marketplace = false,
    bool authenticated = false,
    List<Product>? recommendedProducts,
    List<Product> newArrivalProducts = const [],
    Map<CommerceMode, List<MarketplaceCard>> modeCards = const {},
  }) async {
    // CONSTRUCTED HERE, NOT IN setUp, and the reason is worth recording
    // because it costs an afternoon to find. testWidgets runs its body
    // inside a fake-async zone; setUp runs outside it. A Completer built in
    // setUp belongs to that outer zone, and Dart schedules a future's
    // completion microtask on the zone the future was CREATED in - so
    // completing it from the test body queues the delivery on a real
    // microtask queue that tester.pump() never flushes. The future then
    // never appears to resolve, and every "...and then it loads" assertion
    // fails while the code under test is perfectly correct.
    repository = RecordingRepository();
    repository.newArrivalProducts = newArrivalProducts;
    marketplaceRepository = RecordingMarketplaceRepository();
    marketplaceRepository.homeCards.addAll(modeCards);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          productsRepositoryProvider.overrideWithValue(repository),
          marketplaceRepositoryProvider
              .overrideWithValue(marketplaceRepository),
          authRepositoryProvider.overrideWith((ref) => TestAuthRepository(
                apiClient: ref.read(apiClientProvider),
                tokenStorage: ref.read(tokenStorageProvider),
                authenticated: authenticated,
              )),
          wishlistControllerProvider.overrideWith(TestWishlistController.new),
          if (recommendedProducts != null)
            recommendedForMeProvider.overrideWith((ref) async => recommendedProducts),
          // The store banner is not what this test measures, but it is on the
          // home screen and it polls. Left real it opens a Dio request that
          // never resolves under the test binding, and the pending timer
          // fails the test - while also putting a call on the wire that this
          // test exists to say does not happen. A single-value stream, so
          // there is nothing outstanding when the tree goes away.
          storeStatusProvider
              .overrideWith((ref) => Stream.value(StoreStatus.unknown())),
          // Same reason as the banner above, for the app-bar title. The shop
          // switcher asks /api/marketplace/mode, which left real opens a Dio
          // request the test binding never resolves. Answered here with the
          // single-shop value, which is also what this test's subject is:
          // the request budget of the ORDINARY home screen.
          marketplaceModeProvider.overrideWith((ref) async => marketplace
              ? const MarketplaceMode(
                  mode: 'MULTI_SHOP_PRODUCTION', multiShop: true)
              : MarketplaceMode.singleShop),
          // The header names the address the order is going to, which is
          // visible content and so genuinely belongs in the first wave - but
          // it is not a PRODUCT request, and this test measures the product
          // request budget. Left real it opens a Dio call the test binding
          // never resolves, and the pending timer fails the test for a reason
          // that has nothing to do with what it is asserting.
          myAddressesProvider.overrideWith((ref) async => marketplace
              ? const [
                  AddressModel(
                    id: 17,
                    fullName: 'Release Customer',
                    mobileNumber: '9000000000',
                    houseNo: '1',
                    area: 'Test Colony',
                    city: 'Maharajganj',
                    state: 'Uttar Pradesh',
                    pincode: '273303',
                    latitude: 27.16231,
                    longitude: 83.940468,
                    defaultAddress: true,
                  ),
                ]
              : const []),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: const HomeScreen(),
            bottomNavigationBar: BottomNavigationBar(
              items: const [
                BottomNavigationBarItem(icon: Icon(Icons.home), label: 'Home'),
                BottomNavigationBarItem(icon: Icon(Icons.category), label: 'Categories'),
                BottomNavigationBarItem(icon: Icon(Icons.receipt), label: 'Orders'),
                BottomNavigationBarItem(icon: Icon(Icons.person), label: 'Profile'),
              ],
            ),
          ),
        ),
      ),
    );
    // One frame is all it takes for a provider watched in build() to fire.
    await tester.pump();
  }

  /// Everything the customer cannot see on the first screen.
  const belowFold = ['new-arrivals', 'trending', 'for-me', 'feed'];

  group('what opening the home screen puts on the wire', () {
    Finder verticalHomeScroll() => find.byWidgetPredicate(
          (widget) =>
              widget is Scrollable && widget.axisDirection == AxisDirection.down,
        );
    testWidgets(
        'marketplace feed starts before categories, brands and offers settle',
        (tester) async {
      await openHome(tester, marketplace: true);
      await tester.pump();

      expect(
        marketplaceRepository.calls,
        containsAll([
          'marketplace-feed:27.16231:83.940468:ONLINE_PURCHASE:ALL:0:12',
          'marketplace-feed:27.16231:83.940468:VISIT_TO_BUY:ALL:0:12',
          'marketplace-feed:27.16231:83.940468:SERVICE_AT_SHOP:ALL:0:12',
        ]),
        reason: 'Each commerce mode must have an independent bounded request; '
            'a Buy Online first page must not hide Visit or Service inventory.',
      );
      expect(repository.calls, isNot(contains('feed')),
          reason: 'marketplace Home must never fall back to Shop #1');
      expect(repository.categories.isCompleted, isFalse);
      expect(repository.brands.isCompleted, isFalse);
      expect(repository.offers.isCompleted, isFalse);
    });

    testWidgets('only the above-the-fold requests go out on the first frame', (tester) async {
      await openHome(tester);

      expect(repository.calls, containsAll(['categories', 'brands', 'offers']));
      for (final call in belowFold) {
        expect(repository.calls, isNot(contains(call)),
            reason: '$call is below the fold and must not compete with the visible content');
      }
    });

    testWidgets('the below-the-fold requests follow once the first wave settles', (tester) async {
      await openHome(tester);

      repository.categories.complete(const []);
      repository.brands.complete(const []);
      repository.offers.complete(const []);
      await tester.pump();
      await tester.pump();

      expect(repository.calls, contains('trending'));
      expect(repository.calls, contains('new-arrivals'));
      expect(repository.calls, contains('feed'));
    });

    testWidgets('one section that is still loading holds the second wave', (tester) async {
      await openHome(tester);

      // Two of three home. The third is what the customer is still waiting
      // on, so the wave has not finished and nothing below should start.
      repository.categories.complete(const []);
      repository.brands.complete(const []);
      await tester.pump();
      await tester.pump();

      for (final call in belowFold) {
        expect(repository.calls, isNot(contains(call)));
      }

      repository.offers.complete(const []);
      await tester.pump();
      await tester.pump();

      expect(repository.calls, contains('trending'));
    });

    testWidgets('a FAILED first wave still opens the gate', (tester) async {
      // The gate waits for the wave to SETTLE, not to succeed. A categories
      // endpoint that is down must not wedge the rest of the page shut -
      // that would turn one broken section into a blank home screen.
      await openHome(tester);

      repository.categories.completeError(StateError('categories endpoint down'));
      repository.brands.completeError(StateError('brands endpoint down'));
      repository.offers.completeError(StateError('coupons endpoint down'));
      await tester.pump();
      await tester.pump();

      expect(repository.calls, contains('trending'));
      expect(repository.calls, contains('new-arrivals'));
      expect(repository.calls, contains('feed'));
    });

    testWidgets('each deferred section is requested once, not once per rebuild', (tester) async {
      await openHome(tester);
      repository.categories.complete(const []);
      repository.brands.complete(const []);
      repository.offers.complete(const []);
      await tester.pump();
      await tester.pump();

      for (var frame = 0; frame < 5; frame++) {
        await tester.pump(const Duration(milliseconds: 16));
      }

      expect(repository.calls.where((c) => c == 'trending'), hasLength(1));
      expect(repository.calls.where((c) => c == 'new-arrivals'), hasLength(1));
      expect(repository.calls.where((c) => c == 'feed'), hasLength(1));
    });

    testWidgets('the personalised section is not requested for a signed-out customer', (tester) async {
      // /recommendations/for-me is scoped to the caller's own order history,
      // so calling it without a session is a request that can only 401.
      await openHome(tester);
      repository.categories.complete(const []);
      repository.brands.complete(const []);
      repository.offers.complete(const []);
      await tester.pump();
      await tester.pump();

      expect(repository.calls, isNot(contains('for-me')));
    });

    testWidgets(
        'empty Visit section does not stop Services or Recommendations below Buy Online',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(393, 852);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      await openHome(
        tester,
        marketplace: true,
        authenticated: true,
        recommendedProducts: const [Product(id: 3, name: 'Recommendation fixture')],
        modeCards: {
          CommerceMode.buyOnline: [
          MarketplaceCard.fromJson(const {
            'productId': 1,
            'name': 'Online fixture',
            'commerceMode': 'ONLINE_PURCHASE',
            'addable': true,
            'priceMode': 'EXACT',
          }),
          ],
          CommerceMode.serviceAtShop: [
          MarketplaceCard.fromJson(const {
            'productId': 2,
            'name': 'Service fixture',
            'commerceMode': 'SERVICE_AT_SHOP',
            'addable': false,
            'priceMode': 'STARTING_FROM',
          }),
          ],
        },
      );
      repository.categories.complete(const []);
      repository.brands.complete(const []);
      repository.offers.complete(const []);
      // Home intentionally contains live loading/polling surfaces, so waiting
      // for every animation to settle can never be a reliable readiness
      // signal. These fixture futures complete synchronously; two frames are
      // enough to publish their values and rebuild the affected sections.
      await tester.pump();
      await tester.pump();

      expect(find.text('Buy Online near you'), findsOneWidget);
      expect(find.text('Visit to Buy'), findsNothing);
      await tester.scrollUntilVisible(
        find.text('Services at Shop'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(find.text('Service fixture'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Recommended for you'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(find.text('Recommendation fixture'), findsOneWidget);
    });

    testWidgets('Visit and Services both render when Buy Online is empty',
        (tester) async {
      await openHome(
        tester,
        marketplace: true,
        modeCards: {
          CommerceMode.visitToBuy: [
          MarketplaceCard.fromJson(const {
            'productId': 4,
            'name': 'In-store fixture',
            'commerceMode': 'VISIT_TO_BUY',
            'addable': false,
            'priceMode': 'EXACT',
          }),
          ],
          CommerceMode.serviceAtShop: [
          MarketplaceCard.fromJson(const {
            'productId': 5,
            'name': 'Repair fixture',
            'commerceMode': 'SERVICE_AT_SHOP',
            'addable': false,
            'priceMode': 'STARTING_FROM',
          }),
          ],
        },
      );
      await tester.pump();
      await tester.pump();

      expect(find.text('Buy Online near you'), findsNothing);
      expect(find.text('Visit to Buy'), findsWidgets);
      await tester.scrollUntilVisible(
        find.text('Services at Shop'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(find.text('Repair fixture'), findsOneWidget);
    });

    testWidgets('all populated commerce sections remain in one vertically scrollable Home',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(393, 852);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      final modeCards = <CommerceMode, List<MarketplaceCard>>{};
      for (final mode in CommerceMode.values) {
        modeCards[mode] = [
          MarketplaceCard.fromJson({
            'productId': mode.index + 10,
            'name': '${mode.label} fixture',
            'commerceMode': mode.wire,
            'addable': mode == CommerceMode.buyOnline,
            'priceMode': 'EXACT',
          }),
        ];
      }
      await openHome(
        tester,
        marketplace: true,
        authenticated: true,
        recommendedProducts: const [Product(id: 99, name: 'Final section fixture')],
        newArrivalProducts: const [Product(id: 98, name: 'New arrival fixture')],
        modeCards: modeCards,
      );
      repository.categories.complete(const []);
      repository.brands.complete(const []);
      repository.offers.complete(const []);
      await tester.pump();
      await tester.pump();

      final bottomNavigationBefore = tester.getRect(find.byType(BottomNavigationBar));
      expect(
        find.byWidgetPredicate(
            (widget) => widget is ListView && widget.scrollDirection == Axis.horizontal),
        findsWidgets,
      );
      expect(find.text('Buy Online near you'), findsOneWidget);
      expect(find.text('Buy Online fixture'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('${CommerceMode.visitToBuy.label} fixture'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(find.text('${CommerceMode.visitToBuy.label} fixture'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('${CommerceMode.serviceAtShop.label} fixture'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(find.text('${CommerceMode.serviceAtShop.label} fixture'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('New arrival fixture'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(repository.calls, contains('new-arrivals'));
      expect(find.text('New arrival fixture'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Final section fixture'),
        300,
        scrollable: verticalHomeScroll().first,
      );
      expect(find.text('Recommended for you'), findsOneWidget);
      expect(tester.takeException(), isNull, reason: 'phone-sized Home must not overflow');
      expect(tester.getRect(find.byType(BottomNavigationBar)), bottomNavigationBefore,
          reason: 'the bottom navigation stays fixed while the Home scrolls');
    });
  });
}
