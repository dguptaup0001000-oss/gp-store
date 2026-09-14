import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/features/categories/presentation/categories_screen.dart';
import 'package:gpstore/features/home/presentation/popular_categories.dart';
import 'package:gpstore/features/marketplace/presentation/category_shops_screen.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/category_products_screen.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';

/// The category shelf on the home screen.
///
/// WHAT THESE PIN. That GP-STORE stops looking like a grocery app is not a
/// thing a test can assert, but three things under it are: the home screen
/// shows a HANDFUL of categories rather than the catalogue, the rest are one
/// tap away rather than gone, and a category leads somewhere that makes sense
/// for the kind of deployment this is.
///
/// THE LAST ONE IS THE ONE THAT BREAKS QUIETLY. Under a marketplace a category
/// must open the shops that sell it; under a single shop it must open the
/// products, exactly as it always has. Getting that backwards gives an
/// existing single-shop customer a shop picker with one shop in it - a screen
/// they cannot use and did not ask for.
void main() {
  List<Category> catalogueOf(int howMany) => [
        for (var i = 1; i <= howMany; i++)
          Category(id: i, name: 'Category $i'),
      ];

  Widget host({
    required List<Category> catalogue,
    bool marketplace = false,
    List<MarketCategory> nearby = const [],
  }) =>
      ProviderScope(
        overrides: [
          categoriesProvider.overrideWith((ref) async => catalogue),
          marketplaceModeProvider.overrideWith((ref) async => marketplace
              ? const MarketplaceMode(mode: 'MULTI_SHOP_PRODUCTION', multiShop: true)
              : MarketplaceMode.singleShop),
          marketCategoriesProvider.overrideWith((ref) async => nearby),
        ],
        child: const MaterialApp(
          home: Scaffold(body: SingleChildScrollView(child: PopularCategories())),
        ),
      );

  testWidgets('a big catalogue still draws a handful, plus a way to the rest',
      (tester) async {
    await tester.pumpWidget(host(catalogue: catalogueOf(24)));
    await tester.pumpAndSettle();

    // Seven and an All tile. A home screen made mostly of category tiles is a
    // menu, and the Categories tab already is one.
    expect(find.text('Category 1'), findsOneWidget);
    expect(find.text('Category 7'), findsOneWidget);
    expect(find.text('Category 8'), findsNothing);
    expect(find.text('All'), findsOneWidget);
  });

  testWidgets('a small catalogue draws what there is and no empty cells',
      (tester) async {
    await tester.pumpWidget(host(catalogue: catalogueOf(3)));
    await tester.pumpAndSettle();

    expect(find.text('Category 3'), findsOneWidget);
    expect(find.text('All'), findsOneWidget);
  });

  testWidgets('All opens the complete catalogue', (tester) async {
    await tester.pumpWidget(host(catalogue: catalogueOf(24)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('All'));
    await tester.pumpAndSettle();

    expect(find.byType(CategoriesScreen), findsOneWidget);
  });

  testWidgets('on a marketplace a category opens the shops that sell it',
      (tester) async {
    await tester.pumpWidget(host(catalogue: catalogueOf(8), marketplace: true));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Category 1'));
    await tester.pumpAndSettle();

    // NOT the products. "Which chemist is open" comes before "which
    // paracetamol", and it is the question a marketplace exists to answer.
    expect(find.byType(CategoryShopsScreen), findsOneWidget);
  });

  testWidgets('under one shop a category opens its products, as it always has',
      (tester) async {
    await tester.pumpWidget(host(catalogue: catalogueOf(8)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Category 1'));
    // Pumped through the route transition rather than settled: the browse
    // screen behind this route fetches, and pumpAndSettle would wait on a
    // request this test has no interest in stubbing. What is being asserted
    // is WHICH screen was pushed, and it is pushed by then.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // §14: an existing customer must not have to learn that a multi-shop
    // architecture exists. A shop picker holding one shop is a screen they
    // cannot use.
    expect(find.byType(CategoryProductsScreen), findsOneWidget);
    expect(find.byType(CategoryShopsScreen), findsNothing);
  });

  testWidgets('the shelf leads with what shops near you actually stock',
      (tester) async {
    // The catalogue's order is 1..8. Nearby, the chemists outnumber everyone:
    // category 6 is stocked by four shops and category 3 by two, so those are
    // the tiles worth a customer's thumb even though they sort late by id.
    await tester.pumpWidget(host(
      catalogue: catalogueOf(8),
      marketplace: true,
      nearby: const [
        MarketCategory(categoryId: 6, name: 'Category 6', shopCount: 4),
        MarketCategory(categoryId: 3, name: 'Category 3', shopCount: 2),
      ],
    ));
    await tester.pumpAndSettle();

    final tiles = tester
        .widgetList<CategoryTile>(find.byType(CategoryTile))
        .map((tile) => tile.category.id)
        .toList();

    expect(tiles.first, 6);
    expect(tiles[1], 3);
    // ORDERED, NOT FILTERED. A category nobody nearby stocks is pushed behind
    // the ones that lead somewhere, never dropped - the customer can still see
    // that GP-STORE sells it.
    expect(tiles, contains(1));
  });

  testWidgets('with no nearby answer yet the catalogue order is used',
      (tester) async {
    // Loading, no address, or a pin nobody delivers to - all three land here,
    // and in all three a blank space where the categories should be is the
    // worst possible answer.
    await tester.pumpWidget(host(catalogue: catalogueOf(8), marketplace: true));
    await tester.pumpAndSettle();

    expect(find.text('Category 1'), findsOneWidget);
  });
}
