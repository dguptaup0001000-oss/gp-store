import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/nearby_shop_page.dart';
import 'package:gpstore/features/address/domain/address_models.dart';
import 'package:gpstore/features/address/presentation/address_providers.dart';
import 'package:gpstore/features/home/presentation/home_load_stage.dart';
import 'package:gpstore/features/home/presentation/nearby_shops_section.dart';
import 'package:gpstore/features/marketplace/presentation/market_shop_card.dart';
import 'package:gpstore/features/marketplace/presentation/marketplace_feed_provider.dart';

/// The shops on the home screen.
///
/// THE SECTION THAT SAYS "MARKETPLACE" WITHOUT SAYING IT, and the one with the
/// most ways to be wrong: it must appear where there is a choice of shop, and
/// must draw absolutely nothing where there is not. A "Shops near you" heading
/// over a list of one is a question nobody asked; the same heading over an
/// empty strip because the customer has no address yet is worse.
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
    required bool marketplace,
    List<AddressModel> addresses = const [],
    List<Storefront> shops = const [],
  }) =>
      ProviderScope(
        overrides: [
          // The gate the rest of the below-the-fold work waits behind. Open,
          // because what is being measured here is what the section decides
          // once it is allowed to decide anything.
          homeBelowFoldReadyProvider.overrideWith((ref) => true),
          marketplaceModeProvider.overrideWith((ref) async => marketplace
              ? const MarketplaceMode(mode: 'MULTI_SHOP_PRODUCTION', multiShop: true)
              : MarketplaceMode.singleShop),
          myAddressesProvider.overrideWith((ref) async => addresses),
          nearbyShopsPageProvider((lat: 26.4499, lng: 80.3319, page: 0, size: 7))
              .overrideWith((ref) async => NearbyShopPage(
                    page: 0,
                    size: 7,
                    totalElements: shops.length,
                    totalPages: (shops.length / 7).ceil(),
                    hasNext: shops.length > 7,
                    shops: shops.take(7).toList(),
                  )),
        ],
        child: const MaterialApp(
          home: Scaffold(body: SingleChildScrollView(child: NearbyShopsSection())),
        ),
      );

  testWidgets('under a single shop it draws nothing at all', (tester) async {
    // §14. An existing customer must not have to learn that a multi-shop
    // architecture exists, and this is the section that would teach them.
    await tester.pumpWidget(host(
      marketplace: false,
      addresses: [address()],
      shops: const [Storefront(shopId: 1, displayName: 'GP Store')],
    ));
    await tester.pumpAndSettle();

    expect(find.text('All nearby shops'), findsNothing);
    expect(find.byType(MarketShopCard), findsNothing);
  });

  testWidgets('with no address it draws nothing rather than an empty state',
      (tester) async {
    // The header already says "Set your address" and that is the thing to do
    // about it. A second empty state here is the same nudge twice.
    await tester.pumpWidget(host(marketplace: true));
    await tester.pumpAndSettle();

    expect(find.text('All nearby shops'), findsNothing);
  });

  testWidgets('on a marketplace it lists the shops, nearest first',
      (tester) async {
    await tester.pumpWidget(host(
      marketplace: true,
      addresses: [address()],
      shops: const [
        Storefront(shopId: 6, displayName: 'Gupta Kirana', distanceKm: 1.2),
        Storefront(shopId: 7, displayName: 'A to Z Mart', distanceKm: 4.1),
      ],
    ));
    await tester.pumpAndSettle();

    expect(find.text('All nearby shops'), findsOneWidget);
    expect(find.text('Gupta Kirana'), findsOneWidget);
    expect(find.text('A to Z Mart'), findsOneWidget);
    // THE ORDER IS THE SERVER'S. Nothing in this widget sorts, and a test that
    // let it would be pinning the wrong thing.
    final drawn = tester
        .widgetList<MarketShopCard>(find.byType(MarketShopCard))
        .map((card) => card.shop.shopId)
        .toList();
    expect(drawn, [6, 7]);
    expect(drawn.first, 6, reason: 'the closest shop must lead the row');
  });

  testWidgets('All is first and shop selection filters the feed back to All',
      (tester) async {
    final container = ProviderContainer(overrides: [
      marketplaceModeProvider.overrideWith((ref) async =>
          const MarketplaceMode(mode: 'MULTI_SHOP_PRODUCTION', multiShop: true)),
      myAddressesProvider.overrideWith((ref) async => [address()]),
      nearbyShopsPageProvider((lat: 26.4499, lng: 80.3319, page: 0, size: 7))
              .overrideWith((ref) async => const NearbyShopPage(
                    page: 0, size: 7, totalElements: 2, totalPages: 1, hasNext: false, shops: [
                      Storefront(shopId: 6, displayName: 'GP Store'),
                      Storefront(shopId: 7, displayName: 'Deepak Hardware'),
                    ])),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        home: Scaffold(body: SingleChildScrollView(child: NearbyShopsSection())),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('All'), findsOneWidget);
    await tester.tap(find.text('Deepak Hardware'));
    expect(container.read(marketplaceShopFilterProvider), 7);
    await tester.tap(find.text('All'));
    expect(container.read(marketplaceShopFilterProvider), isNull);
    container.dispose();
  });

  testWidgets('a marketplace with nobody in range draws nothing',
      (tester) async {
    // Empty is an honest answer and the shop picker gives it properly, with
    // the ladder and a way to look farther. A blank heading on the home screen
    // would be the same fact told worse.
    await tester.pumpWidget(host(marketplace: true, addresses: [address()]));
    await tester.pumpAndSettle();

    expect(find.text('All nearby shops'), findsNothing);
  });

  testWidgets('See all appears only when there are more than the row shows',
      (tester) async {
    await tester.pumpWidget(host(
      marketplace: true,
      addresses: [address()],
      shops: const [
        Storefront(shopId: 1, displayName: 'One'),
        Storefront(shopId: 2, displayName: 'Two'),
      ],
    ));
    await tester.pumpAndSettle();

    expect(find.text('See all'), findsNothing,
        reason: 'both shops are already on screen, so "See all" leads nowhere new');
  });

  testWidgets('See all opens for a paginated nearby shop list', (tester) async {
    await tester.pumpWidget(host(
      marketplace: true,
      addresses: [address()],
      shops: List.generate(
        13,
        (index) => Storefront(shopId: index + 1, displayName: 'Test shop ${index + 1}'),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('See all'), findsOneWidget);
  });
}
