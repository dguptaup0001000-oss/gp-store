import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/admin_products_repository.dart';
import 'package:gpstore/features/admin/domain/selling_mode.dart';
import 'package:gpstore/features/admin/presentation/admin_providers.dart';
import 'package:gpstore/features/admin/presentation/merchant_mode_catalogue_screen.dart';

import '../../../support/test_api_client.dart';

/// The Visit to Buy and Services screens, through the real repository.
///
/// <h2>What this is guarding</h2>
///
/// This feature has already failed once by being invisible: the backend, the
/// migration and the tests all existed while no merchant could reach the
/// screen. The second way to be invisible is quieter - a list that fetches
/// one page and draws it, so a merchant with 31 items sees thirty and has no
/// idea the thirty-first exists. Both are the same failure: the data is
/// there and the shopkeeper cannot see it.
///
/// So these tests assert what the merchant can actually reach, and they go
/// through the real AdminProductsRepository against a fake adapter, so the
/// query parameters and the JSON shape are the real ones.
void main() {
  setUpAll(setUpFakeSecureStorage);

  /// A page of listings, all in one mode.
  Map<String, dynamic> page(String mode, int index, {int total = 44, int size = 30}) {
    final start = index * size;
    final count = (total - start).clamp(0, size);
    return {
      'content': [
        for (var i = 0; i < count; i++)
          {
            'productVariantId': start + i + 1,
            'productId': start + i + 1,
            'name': 'Item ${start + i}',
            'brand': null,
            'imageUrl': null,
            'categoryId': 3,
            'categoryName': 'Jewellery',
            'variantLabel': '1 pc',
            'sellingPrice': 4500,
            'mrp': null,
            'priceMax': null,
            'priceMode': 'EXACT_PRICE',
            'commerceMode': mode,
            'commerceLabel': mode == 'SERVICE_AT_SHOP' ? 'Service at Shop' : 'Visit to Buy',
            'offlineAvailability': 'AVAILABLE',
            'serviceDurationMinutes': mode == 'SERVICE_AT_SHOP' ? 45 : null,
            'available': true,
            'active': true,
            'stock': null,
          }
      ],
      'page': index,
      'size': size,
      'totalElements': total,
      'totalPages': (total / size).ceil(),
    };
  }

  void tall(WidgetTester tester) {
    tester.view.physicalSize = const Size(1100, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  ({Widget widget, List<Map<String, dynamic>> calls}) host(
    SellingMode mode, {
    int total = 44,
  }) {
    final calls = <Map<String, dynamic>>[];
    final adapter = FakeHttpClientAdapter();
    adapter.on('GET', '/api/shop/catalogue', (options) {
      calls.add(Map<String, dynamic>.from(options.queryParameters));
      final index = (options.queryParameters['page'] as num?)?.toInt() ?? 0;
      final wanted = options.queryParameters['mode'] as String;
      final q = (options.queryParameters['q'] as String?)?.trim() ?? '';
      if (q.isNotEmpty) {
        // A search narrows to one row, so "results replaced the list" is
        // distinguishable from "results were appended to it".
        return FakeResponse({
          ...page(wanted, 0, total: 1, size: 30),
        });
      }
      return FakeResponse(page(wanted, index, total: total));
    });

    return (
      calls: calls,
      widget: ProviderScope(
        overrides: [
          adminProductsRepositoryProvider.overrideWithValue(
              AdminProductsRepository(apiClient: buildTestApiClient(adapter))),
        ],
        child: MaterialApp(home: MerchantModeCatalogueScreen(mode: mode)),
      ),
    );
  }

  group('Visit to Buy', () {
    testWidgets('asks the server for its own mode and nothing else',
        (tester) async {
      tall(tester);
      final h = host(SellingMode.visitToBuy);
      await tester.pumpWidget(h.widget);
      await tester.pumpAndSettle();

      expect(h.calls.single['mode'], 'VISIT_TO_BUY');
      expect(find.text('Visit to Buy'), findsWidgets);
    });

    /// THE BUG THIS EXISTS FOR. Thirty of forty-four, with no way to the rest,
    /// is a merchant's stock quietly disappearing off the end of their own
    /// screen.
    testWidgets('does not stop at the first page', (tester) async {
      tall(tester);
      final h = host(SellingMode.visitToBuy);
      await tester.pumpWidget(h.widget);
      await tester.pumpAndSettle();

      expect(find.text('Item 0'), findsOneWidget);
      expect(find.text('Item 29'), findsOneWidget);
      expect(find.text('Item 30'), findsNothing);
      expect(find.text('Show more (30 of 44)'), findsOneWidget);

      await tester.tap(find.text('Show more (30 of 44)'));
      await tester.pumpAndSettle();

      expect(h.calls.map((c) => c['page']).toList(), [0, 1]);
      // The first page is still there - a "show more" that replaces the list
      // is a pager pretending to be an accumulator.
      expect(find.text('Item 0'), findsOneWidget);
      expect(find.text('Item 30'), findsOneWidget);
      expect(find.textContaining('Show more'), findsNothing,
          reason: 'there is nothing left to show');

      // The footer is gone because 44 of 44 are now in the list. Asserting on
      // "Item 43" itself would be an assertion about the test window's
      // height: a lazy ListView has not built the last row yet, and adding
      // a scroll here would test Flutter's scrolling rather than this
      // screen's paging.
    });

    testWidgets('a search replaces the list rather than adding to it',
        (tester) async {
      tall(tester);
      final h = host(SellingMode.visitToBuy);
      await tester.pumpWidget(h.widget);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Show more (30 of 44)'));
      await tester.pumpAndSettle();
      expect(find.text('Item 30'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'ring');
      await tester.pumpAndSettle(const Duration(seconds: 2));

      expect(find.text('Item 30'), findsNothing,
          reason: 'the pages fetched before the search are still on screen');
      expect(find.text('Item 0'), findsOneWidget);
      expect(h.calls.last['q'], 'ring');
      expect(h.calls.last['page'], 0);
    });

    testWidgets("offers no way to buy anything - this is the shop's own list",
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host(SellingMode.visitToBuy).widget);
      await tester.pumpAndSettle();

      // The merchant console never had a cart; this asserts it stayed that
      // way when a mode whose whole point is "not buyable online" got its
      // own screen. The refusal that actually matters is the server's, in
      // WhatIsNotSoldOnlineCannotBeBoughtOnlineTest.
      for (final forbidden in ['Add to Cart', 'Buy Now', 'Checkout', 'ADD']) {
        expect(find.text(forbidden), findsNothing, reason: forbidden);
      }
    });
  });

  group('Services at Shop', () {
    testWidgets('is the same screen asking for the other mode', (tester) async {
      tall(tester);
      final h = host(SellingMode.serviceAtShop, total: 2);
      await tester.pumpWidget(h.widget);
      await tester.pumpAndSettle();

      expect(h.calls.single['mode'], 'SERVICE_AT_SHOP');
      expect(find.text('Services at Shop'), findsWidgets);
      expect(find.text('Add service'), findsOneWidget);
    });

    testWidgets('has nothing to say about stock, because a job has none',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host(SellingMode.serviceAtShop, total: 2).widget);
      await tester.pumpAndSettle();

      expect(find.textContaining('in stock'), findsNothing);
      expect(find.textContaining('Stock'), findsNothing);
    });
  });

  group('An empty shelf', () {
    testWidgets('explains what the mode is for rather than saying "no data"',
        (tester) async {
      tall(tester);
      await tester.pumpWidget(host(SellingMode.visitToBuy, total: 0).widget);
      await tester.pumpAndSettle();

      expect(find.text('Nothing listed to visit for yet'), findsOneWidget);
      expect(find.textContaining('no cart and no online'), findsOneWidget,
          reason: 'the empty state should say what the mode is FOR');
      expect(find.text('No data'), findsNothing);
      expect(find.text('Add item'), findsOneWidget);
    });
  });
}
