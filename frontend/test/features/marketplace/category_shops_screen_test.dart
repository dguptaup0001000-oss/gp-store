import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/marketplace/presentation/category_shops_screen.dart';
import 'package:gpstore/features/marketplace/presentation/market_shop_card.dart';

/// Choosing a chemist before choosing a paracetamol.
///
/// THE SCREEN THE MARKETPLACE WAS MISSING. Tapping "Medicine" used to open the
/// medicine of whichever single shop the app happened to be acting for, which
/// is the right answer for a grocery app with one kirana behind it. What these
/// pin is the marketplace behaviour: the shops come first, the ladder still
/// works inside a category, and an empty answer explains itself rather than
/// looking broken.
void main() {
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

  Widget host({
    required DiscoveryPage page,
    List<AddressModel> addresses = const [],
  }) =>
      ProviderScope(
        overrides: [
          myAddressesProvider.overrideWith((ref) async => addresses),
          categoryShopsProvider(12).overrideWith((ref) async => page),
        ],
        child: const MaterialApp(
          home: CategoryShopsScreen(categoryId: 12, categoryName: 'Medicine'),
        ),
      );

  testWidgets('it names the category and lists the shops that sell it',
      (tester) async {
    await tester.pumpWidget(host(
      addresses: [address()],
      page: const DiscoveryPage(shops: [
        Storefront(
            shopId: 6,
            displayName: 'Sharma Medical',
            distanceKm: 1.4,
            ratingAverage: 4.4,
            ratingCount: 210),
        Storefront(shopId: 7, displayName: 'City Chemist', distanceKm: 3.9),
      ]),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Medicine'), findsOneWidget);
    expect(find.text('Shops near you'), findsOneWidget);
    expect(find.text('Sharma Medical'), findsOneWidget);
    expect(find.text('City Chemist'), findsOneWidget);
    expect(find.text('4.4 (210)'), findsOneWidget);
  });

  testWidgets('the order is the one the server sent', (tester) async {
    await tester.pumpWidget(host(
      addresses: [address()],
      page: const DiscoveryPage(shops: [
        Storefront(shopId: 9, displayName: 'Nearest'),
        Storefront(shopId: 3, displayName: 'Farther'),
      ]),
    ));
    await tester.pumpAndSettle();

    final drawn = tester
        .widgetList<MarketShopCard>(find.byType(MarketShopCard))
        .map((card) => card.shop.shopId)
        .toList();
    // §4: reuse the existing ranking, do not invent one. A screen that sorted
    // by id, or by name, or by rating would be a second ranking algorithm
    // competing with the marketplace's.
    expect(drawn, [9, 3]);
  });

  testWidgets("the server's own sentence is printed when it widened the search",
      (tester) async {
    await tester.pumpWidget(host(
      addresses: [address()],
      page: const DiscoveryPage(
        widened: true,
        message: 'No shops within 2 km. Showing shops within 8 km.',
        shops: [Storefront(shopId: 6, displayName: 'Sharma Medical')],
      ),
    ));
    await tester.pumpAndSettle();

    // NOT COMPOSED HERE. The widening decision and the words describing it are
    // made in the same place, so a change to the ladder cannot leave the app
    // telling customers something that is no longer true.
    expect(find.text('No shops within 2 km. Showing shops within 8 km.'),
        findsOneWidget);
  });

  testWidgets('nothing nearby sells it, and the next rung is offered',
      (tester) async {
    await tester.pumpWidget(host(
      addresses: [address()],
      page: const DiscoveryPage(nextRadiusKm: 8),
    ));
    await tester.pumpAndSettle();

    expect(find.text('No shop near you sells this yet'), findsOneWidget);
    // The ladder is the server's, including which rung comes next.
    expect(find.text('Look within 8 km'), findsOneWidget);
  });

  testWidgets('the top of the ladder offers no further search', (tester) async {
    await tester.pumpWidget(host(
      addresses: [address()],
      page: const DiscoveryPage(),
    ));
    await tester.pumpAndSettle();

    expect(find.text('No shop near you sells this yet'), findsOneWidget);
    expect(find.textContaining('Look within'), findsNothing,
        reason: 'nextRadiusKm is null, so there is nowhere farther to go and '
            'offering a search would repeat the same empty answer');
  });

  testWidgets('with no address it asks for one rather than showing nothing',
      (tester) async {
    await tester.pumpWidget(host(page: const DiscoveryPage()));
    await tester.pumpAndSettle();

    expect(find.text('Add a delivery address first'), findsOneWidget);
  });
}
