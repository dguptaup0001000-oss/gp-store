import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/marketplace/presentation/category_shops_screen.dart';
import 'package:gpstore/features/marketplace/presentation/shop_profile_screen.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/products_providers.dart';
import 'package:gpstore/features/products/presentation/search_matches.dart';

/// Search has three answers on a marketplace, not one.
///
/// "Sharma Medical" is a shop, "medicine" is a category and "crocin" is a
/// product. A search box that only ever answers the third sends somebody
/// looking for a chemist through a list of paracetamol strips to find one -
/// which is what GP-STORE's search did.
void main() {
  const pin = (lat: 26.4499, lng: 80.3319);

  AddressModel address() => const AddressModel(
        id: 1,
        fullName: 'Deepak Gupta',
        mobileNumber: '9999999999',
        houseNo: '12',
        area: 'Civil Lines',
        city: 'Kanpur',
        state: 'Uttar Pradesh',
        pincode: '208001',
        latitude: 26.4499,
        longitude: 80.3319,
        defaultAddress: true,
      );

  Widget host(
    String query, {
    bool marketplace = true,
    List<Storefront> shops = const [],
    List<Category> categories = const [],
    bool withAddress = true,
  }) =>
      ProviderScope(
        overrides: [
          marketplaceModeProvider.overrideWith((ref) async => marketplace
              ? const MarketplaceMode(mode: 'MULTI_SHOP_PRODUCTION', multiShop: true)
              : MarketplaceMode.singleShop),
          myAddressesProvider
              .overrideWith((ref) async => withAddress ? [address()] : const []),
          shopsNearProvider(pin).overrideWith((ref) async => shops),
          categoriesProvider.overrideWith((ref) async => categories),
        ],
        child: MaterialApp(
          home: Scaffold(body: SingleChildScrollView(child: SearchMatches(query: query))),
        ),
      );

  testWidgets('a shop name finds the shop', (tester) async {
    await tester.pumpWidget(host('sharma', shops: const [
      Storefront(shopId: 6, displayName: 'Sharma Medical', distanceKm: 1.4),
      Storefront(shopId: 7, displayName: 'Gupta Kirana', distanceKm: 0.8),
    ]));
    await tester.pumpAndSettle();

    expect(find.text('Shops'), findsOneWidget);
    expect(find.text('Sharma Medical'), findsOneWidget);
    expect(find.text('Gupta Kirana'), findsNothing);
    expect(find.text('1.4 km'), findsOneWidget);
  });

  testWidgets('tapping a matched shop opens that shop', (tester) async {
    await tester.pumpWidget(host('sharma', shops: const [
      Storefront(shopId: 6, displayName: 'Sharma Medical'),
    ]));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Sharma Medical'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.byType(ShopProfileScreen), findsOneWidget);
  });

  testWidgets('a category name finds the category, and opens its shops',
      (tester) async {
    await tester.pumpWidget(host('medi', categories: const [
      Category(id: 3, name: 'Medicine & Healthcare'),
      Category(id: 4, name: 'Fruits & Vegetables'),
    ]));
    await tester.pumpAndSettle();

    expect(find.text('Medicine & Healthcare'), findsOneWidget);
    expect(find.text('Fruits & Vegetables'), findsNothing);

    await tester.tap(find.text('Medicine & Healthcare'));
    // Pumped through the transition rather than settled: the screen behind
    // this route asks the server which shops sell the category, and
    // pumpAndSettle would wait on a request this test has no interest in
    // stubbing. Which screen was pushed is decided by then.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.byType(CategoryShopsScreen), findsOneWidget);
  });

  testWidgets('the shops keep the order the marketplace ranked them in',
      (tester) async {
    await tester.pumpWidget(host('medical', shops: const [
      Storefront(shopId: 9, displayName: 'Zeta Medical', distanceKm: 0.5),
      Storefront(shopId: 2, displayName: 'Alpha Medical', distanceKm: 3.0),
    ]));
    await tester.pumpAndSettle();

    // FILTERED, NEVER RE-SORTED. Both match; the nearer one arrived first and
    // must stay first, rather than being alphabetised by this screen.
    final rows = tester
        .widgetList<Text>(find.byType(Text))
        .map((t) => t.data)
        .where((t) => t != null && t.endsWith('Medical'))
        .toList();
    expect(rows, ['Zeta Medical', 'Alpha Medical']);
  });

  testWidgets('under a single shop no shop matches are offered', (tester) async {
    // §14. There is no choice of shop to make, so "Shops" over a list of one
    // is a section answering a question nobody asked.
    await tester.pumpWidget(host('sharma',
        marketplace: false,
        shops: const [Storefront(shopId: 6, displayName: 'Sharma Medical')]));
    await tester.pumpAndSettle();

    expect(find.text('Shops'), findsNothing);
  });

  testWidgets('one letter matches nothing, so nothing is drawn', (tester) async {
    // A section that pushed every shop and category off the top of the screen
    // on the first keystroke would answer a question the customer had not
    // finished asking.
    await tester.pumpWidget(host('s', shops: const [
      Storefront(shopId: 6, displayName: 'Sharma Medical'),
    ]));
    await tester.pumpAndSettle();

    expect(find.text('Shops'), findsNothing);
  });

  testWidgets('nothing matching draws no section at all', (tester) async {
    await tester.pumpWidget(host('xyzzy', shops: const [
      Storefront(shopId: 6, displayName: 'Sharma Medical'),
    ], categories: const [
      Category(id: 3, name: 'Medicine'),
    ]));
    await tester.pumpAndSettle();

    expect(find.text('Shops'), findsNothing);
    expect(find.text('Categories'), findsNothing);
  });
}
