import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/features/marketplace/presentation/market_shop_card.dart';

/// One shop, drawn the same way everywhere it appears.
///
/// WHAT THESE PIN is not the layout. It is the handful of places where the
/// honest answer and the convenient one differ: a shop nobody has rated is not
/// a nought out of five, a shop that will not deliver here is shown saying so
/// rather than hidden, and a shut shop is still worth browsing.
void main() {
  Widget host(Widget child) => ProviderScope(
        child: MaterialApp(home: Scaffold(body: child)),
      );

  const rated = Storefront(
    shopId: 6,
    displayName: 'Gupta Kirana Store',
    distanceKm: 2.5,
    maxDeliveryRadiusKm: 8,
    ratingAverage: 4.6,
    ratingCount: 320,
  );

  testWidgets('a rated shop shows its stars and how many said so',
      (tester) async {
    await tester.pumpWidget(host(const MarketShopCard(shop: rated)));
    await tester.pumpAndSettle();

    expect(find.text('4.6 (320)'), findsOneWidget);
    expect(find.textContaining('2.5 km'), findsOneWidget);
    expect(find.textContaining('delivers to 8 km'), findsOneWidget);
  });

  testWidgets('a shop nobody has rated is New, not nought out of five',
      (tester) async {
    // THE ASSERTION THAT MATTERS MOST ON THIS CARD. The server sends a null
    // average with a zero count for a shop nobody has rated, and the tempting
    // render is "0.0 ★" - which is this app publishing a one-word review of a
    // real merchant who has simply not been rated yet.
    await tester.pumpWidget(host(const MarketShopCard(
      shop: Storefront(shopId: 7, displayName: 'Brand New Stores'),
    )));
    await tester.pumpAndSettle();

    expect(find.text('New'), findsOneWidget);
    expect(find.textContaining('0.0'), findsNothing);
  });

  testWidgets('a shop outside its own radius is shown, and says so',
      (tester) async {
    // SEARCHING FARTHER WIDENS WHAT A CUSTOMER SEES, not what any shop
    // promised. Hiding this shop would be the app making the shop's decision;
    // showing it without the warning would be the app making the customer's.
    await tester.pumpWidget(host(const MarketShopCard(
      shop: Storefront(
          shopId: 8, displayName: 'Far Away Mart', deliversHere: false),
    )));
    await tester.pumpAndSettle();

    expect(find.text("Doesn't deliver to this address"), findsOneWidget);
  });

  testWidgets('browsing is never closed, but a shut shop says it is shut',
      (tester) async {
    await tester.pumpWidget(host(const MarketShopCard(
      shop: Storefront(
          shopId: 9,
          displayName: 'Closed Today Stores',
          closedToday: true,
          closureReason: 'Diwali'),
    )));
    await tester.pumpAndSettle();

    expect(find.text('Closed today - Diwali'), findsOneWidget);
    // The shop is still on screen and still tappable: a shut kirana is worth
    // looking at, and what changes is when the order arrives.
    expect(find.text('Closed Today Stores'), findsOneWidget);
  });

  testWidgets('the three ways a shop can be shut are three different sentences',
      (tester) async {
    expect(
      MarketShopCard.whenItIsShut(const Storefront(
          shopId: 1, closedToday: true, closureReason: 'stock-take')),
      'Closed today - stock-take',
    );
    expect(
      MarketShopCard.whenItIsShut(
          const Storefront(shopId: 1, acceptingOrders: false, pausedUntil: '4pm')),
      'Paused until 4pm',
      reason: '"back at four" and "closed" are different facts, and a customer '
          'deciding where to buy from needs the difference',
    );
    expect(
      MarketShopCard.whenItIsShut(const Storefront(shopId: 1, openNow: false)),
      'Closed right now',
    );
    expect(MarketShopCard.whenItIsShut(const Storefront(shopId: 1)), isNull);
  });
}
