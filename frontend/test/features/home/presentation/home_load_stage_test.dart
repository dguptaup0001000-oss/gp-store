import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/home/presentation/home_screen.dart';
import 'package:gpstore/features/products/data/products_repository.dart';
import 'package:gpstore/features/products/domain/brand_models.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

import '../../../support/test_api_client.dart';

/// Records every call the home screen makes, and lets the above-the-fold
/// wave be held open so the gate can be observed while it is still shut.
class RecordingRepository implements ProductsRepository {
  final List<String> calls = [];

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
    return const [];
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
  Future<ProductPage> fetchFeed({int page = 0, int size = 20}) async {
    calls.add('feed');
    return const ProductPage(products: [], page: 0, hasNext: false, totalElements: 0);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  setUpAll(setUpFakeSecureStorage);

  late RecordingRepository repository;

  Future<void> openHome(WidgetTester tester) async {
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

    await tester.pumpWidget(
      ProviderScope(
        overrides: [productsRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: HomeScreen()),
      ),
    );
    // One frame is all it takes for a provider watched in build() to fire.
    await tester.pump();
  }

  /// Everything the customer cannot see on the first screen.
  const belowFold = ['new-arrivals', 'trending', 'for-me', 'feed'];

  group('what opening the home screen puts on the wire', () {
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
  });
}
