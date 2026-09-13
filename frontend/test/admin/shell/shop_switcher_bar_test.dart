import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/shell/shop_switcher_bar.dart';
import 'package:gpstore/features/admin/domain/shop_admin_models.dart';
import 'package:gpstore/features/admin/presentation/shop_self_service_providers.dart';

/// The switcher, and the single-shop app it must not disturb.
void main() {
  Widget host(MyShops shops) => ProviderScope(
        overrides: [
          myShopsProvider.overrideWith((ref) async => shops),
        ],
        child: const MaterialApp(
          home: Scaffold(body: ShopSwitcherBar()),
        ),
      );

  const gpStore = ShopChoice(
      shopId: 1, code: 'SHOP-1', displayName: 'GP Store',
      status: 'ACTIVE', operable: true, acting: true);
  const hardware = ShopChoice(
      shopId: 2, code: 'DEEPAK-HARDWARE', displayName: 'Deepak Hardware',
      status: 'ACTIVE', operable: true);
  const saree = ShopChoice(
      shopId: 3, code: 'DEEPAK-SAREE', displayName: 'Deepak Saree',
      status: 'CLOSED', operable: false);

  testWidgets('with one shop the switcher is not there at all', (tester) async {
    await tester.pumpWidget(host(const MyShops(shops: [gpStore], acting: 1)));
    await tester.pumpAndSettle();

    // §59. A shopkeeper with a single kirana must not be handed a marketplace
    // administration console. Not merely hidden - absent, so it takes no space
    // and nothing about the single-shop app changes.
    expect(find.text('Switch'), findsNothing);
    expect(find.text('GP Store'), findsNothing);
    expect(find.byType(SizedBox), findsWidgets);
  });

  testWidgets('with several shops the current one is named on screen',
      (tester) async {
    await tester.pumpWidget(
        host(const MyShops(shops: [gpStore, hardware], acting: 1)));
    await tester.pumpAndSettle();

    // §64. Before this the admin shell showed no shop name anywhere, because
    // there was only ever one shop to be in. With two, a merchant who believes
    // they are in GP Store and is actually in Deepak Hardware changes the
    // wrong prices and finds out when a customer complains.
    expect(find.text('GP Store'), findsOneWidget);
    expect(find.text('Switch'), findsOneWidget);
  });

  testWidgets('the sheet lists every shop and ticks the current one',
      (tester) async {
    await tester.pumpWidget(
        host(const MyShops(shops: [gpStore, hardware, saree], acting: 1)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Switch'));
    await tester.pumpAndSettle();

    expect(find.text('My shops'), findsOneWidget);
    expect(find.text('Deepak Hardware'), findsOneWidget);
    expect(find.text('Deepak Saree'), findsOneWidget);
    expect(find.byIcon(Icons.check_circle), findsOneWidget);
  });

  testWidgets('a closed shop is listed but cannot be entered', (tester) async {
    await tester.pumpWidget(
        host(const MyShops(shops: [gpStore, hardware, saree], acting: 1)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Switch'));
    await tester.pumpAndSettle();

    // LISTED, BECAUSE IT IS STILL THEIRS - hiding a shop a merchant owns makes
    // them think it was deleted. NOT ENTERABLE, because there is nothing left
    // to administer and the screen would error on arrival, which reads as the
    // app being broken rather than the shop being shut.
    final tile = tester.widget<ListTile>(
        find.ancestor(of: find.text('Deepak Saree'), matching: find.byType(ListTile)));
    expect(tile.enabled, isFalse);
    expect(find.textContaining('nothing to manage here'), findsOneWidget);
  });

  testWidgets('a failure in the shop list does not break every screen',
      (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        myShopsProvider.overrideWith((ref) async => throw Exception('offline')),
      ],
      child: const MaterialApp(home: Scaffold(body: ShopSwitcherBar())),
    ));
    await tester.pumpAndSettle();

    // THIS SITS IN THE CHROME OF EVERY SCREEN. A red banner here because one
    // secondary call was slow would interrupt the merchant's whole app. If we
    // cannot say which shop this is, saying nothing beats saying something
    // that might be wrong.
    expect(tester.takeException(), isNull);
    expect(find.text('Switch'), findsNothing);
  });
}
