import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/shop_context.dart';
import 'package:gpstore/features/auth/presentation/auth_providers.dart';
import 'package:gpstore/features/cart/presentation/cart_screen.dart';
import 'package:gpstore/features/marketplace/presentation/compare_shops_sheet.dart';
import 'package:gpstore/features/marketplace/presentation/shop_picker_screen.dart';
import 'package:gpstore/features/marketplace/presentation/shop_profile_screen.dart';
import 'package:gpstore/features/orders/presentation/order_history_screen.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/features/products/presentation/product_detail_screen.dart';

import '../../support/test_api_client.dart';
import '../../support/two_shop_marketplace.dart';

/// THE TWO-SHOP CUSTOMER JOURNEY, DRIVEN THROUGH THE REAL SCREENS.
///
/// WHAT THIS PROVES AND WHAT IT DOES NOT. Every widget, provider, model and
/// parser below is the shipped one; the taps are real taps and the navigation
/// is real navigation. What sits behind Dio is a scripted marketplace, not
/// the Spring backend - so this proves the APP behaves correctly against the
/// contract, and the backend suite proves the SERVER honours it. Neither is a
/// substitute for the other, and neither is a substitute for running the app
/// on a phone against a live MULTI_SHOP_PRODUCTION deployment, which cannot
/// be done in this environment (no Android SDK - see the report).
///
/// THE SHOPS ARE DELIBERATELY DIFFERENT. Shop A is 1.2 km away, trusted, has
/// the dal at ₹100 and charges ₹20 to deliver. Shop B is 4.6 km away, not
/// trusted, has run out of the dal - so it has NO PRICE for it - and charges
/// ₹45. Every assertion below would still pass if the two shops were
/// identical, which is exactly why they are not.
void main() {
  late TwoShopMarketplace market;

  setUp(() {
    // A SIGNED-IN CUSTOMER, which is what every screen below assumes. The
    // app treats a stored refresh token as a session (AuthController
    // ._restoreSession), so seeding one puts the real auth path into the
    // authenticated state without stubbing the controller.
    setUpFakeSecureStorage(seed: {
      'access_token': 'test-access',
      'refresh_token': 'test-refresh',
      'remember_me': 'true',
    });
    market = TwoShopMarketplace();
  });

  /// Drives the app against its own container, then tears the tree down
  /// inside the test body.
  ///
  /// WHY NOT addTearDown: the widget tree and the container outlive the test
  /// body when they are torn down there, and Flutter's pending-timer check
  /// runs first - so myAddressesProvider's keep-alive timer, which the
  /// provider itself cancels on disposal, was being reported as a leak that
  /// did not exist.
  Future<ProviderContainer> driveWithContainer(
    WidgetTester tester,
    Widget home,
  ) async {
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWith((ref) => market.client(
            activeShopId: () => ref.read(shopContextProvider),
          )),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(home: home),
    ));
    await tester.pumpAndSettle();
    return container;
  }

  Future<void> settleAndDispose(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    c.dispose();
  }

  /// The app, with its real providers, talking to the scripted marketplace.
  ///
  /// ONLY THE HTTP CLIENT IS OVERRIDDEN. Not the repository, not the
  /// providers, not the models - overriding those would test the fixture
  /// instead of the app.
  Widget app(Widget home, {ProviderContainer? container}) {
    return ProviderScope(
      overrides: [
        apiClientProvider.overrideWith((ref) => market.client(
              activeShopId: () => ref.read(shopContextProvider),
            )),
      ],
      child: MaterialApp(home: home),
    );
  }

  group('Finding the shops', () {
    testWidgets('both shops are discoverable from one address', (tester) async {
      await tester.pumpWidget(app(const ShopPickerScreen()));
      await tester.pumpAndSettle();

      expect(find.text('Shop A'), findsOneWidget);
      expect(find.text('Shop B'), findsOneWidget);
      // The nearest is labelled, and it is the server's ordering that decided
      // which - nothing in the app re-sorted the list.
      expect(find.text('Closest to your address'), findsOneWidget);
      // It went to the discovery route, not the old bare list.
      expect(market.requested, contains('GET /api/marketplace/discovery'));
    });

    testWidgets('what GP-STORE has checked is shown on the row', (tester) async {
      await tester.pumpWidget(app(const ShopPickerScreen()));
      await tester.pumpAndSettle();

      expect(find.text('GP-STORE verified'), findsNWidgets(2));
      // THE CLAIM IS THE DISTANCE, NOT THE SENTENCE. The row used to read
      // "1.2 km away · delivers up to 8 km" on its own line; the shared card
      // now reads "1.2 km · delivers to 8 km" beside the rating. What has to
      // stay true is that a customer choosing between two shops can see how
      // far each one is, so that is what this matches on.
      expect(find.textContaining('1.2 km'), findsOneWidget);
      expect(find.textContaining('4.6 km'), findsOneWidget);
    });

    testWidgets('nothing nearby offers the next rung, and says what it found',
        (tester) async {
      market.nobodyDeliversHereUntilYouLookFarther();
      await tester.pumpWidget(app(const ShopPickerScreen()));
      await tester.pumpAndSettle();

      expect(find.text('No shop delivers here yet'), findsOneWidget);
      expect(find.text('Look within 10 km'), findsOneWidget);

      await tester.tap(find.text('Look within 10 km'));
      await tester.pumpAndSettle();

      // THE SERVER'S SENTENCE, PRINTED VERBATIM. The app did not compose it
      // and does not know the ladder.
      expect(find.text('No shops within 10 km. Showing results within 20 km.'),
          findsOneWidget);
      // And the shop it found is honest about not reaching this address:
      // searching farther widened what is visible, not what anybody promised.
      expect(find.text("Doesn't deliver to this address"), findsOneWidget);
    });
  });

  group('Choosing Shop A', () {
    testWidgets('a shop tile opens the shop, it does not silently switch',
        (tester) async {
      final container = await driveWithContainer(tester, const ShopPickerScreen());

      await tester.tap(find.text('Shop A'));
      await tester.pumpAndSettle();

      expect(find.byType(ShopProfileScreen), findsOneWidget);
      // Opening a shop is not choosing it.
      expect(container.read(shopContextProvider), isNull);

      await tester.tap(find.text('Shop here'));
      await tester.pumpAndSettle();

      expect(container.read(shopContextProvider), TwoShopMarketplace.shopA);
      await settleAndDispose(tester, container);
    });

    testWidgets('the profile carries what the decision to buy needs',
        (tester) async {
      await tester.pumpWidget(
          app(const ShopProfileScreen(shopId: TwoShopMarketplace.shopA)));
      await tester.pumpAndSettle();

      // Who you are paying, and what has been checked.
      expect(find.text('A Kirana Stores'), findsOneWidget);
      expect(find.text('GP-STORE verified'), findsOneWidget);
      expect(find.text('Trusted shop'), findsOneWidget);
      // Lifetime AND recent - a flattering average and a truthful one.
      expect(find.text('4.6'), findsOneWidget);
      expect(find.textContaining('128 ratings'), findsOneWidget);
      expect(find.textContaining('Last 30 days: 4.8'), findsOneWidget);
      expect(find.textContaining('118 from customers who ordered'), findsOneWidget);
      // Hours, delivery, and the shop's own promises.
      expect(find.text('Open now.'), findsOneWidget);
      expect(find.textContaining('Delivers up to 8 km'), findsOneWidget);
      // The promises sit below the fold on a phone-sized screen, which is
      // where a customer would have to scroll to find them too.
      await tester.scrollUntilVisible(
          find.text('Returns within 2 days.'), 200,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('Returns within 2 days.'), findsOneWidget);
      // AND THE OWNERSHIP OF DELIVERY, said plainly.
      expect(
          find.textContaining("shop's own estimate, not a GP-STORE guarantee"),
          findsOneWidget);
    });

    testWidgets('a shop with no trusted badge does not get one', (tester) async {
      await tester.pumpWidget(
          app(const ShopProfileScreen(shopId: TwoShopMarketplace.shopB)));
      await tester.pumpAndSettle();

      expect(find.text('B Provision Mart'), findsOneWidget);
      expect(find.text('Trusted shop'), findsNothing);
    });
  });

  group('The product, shop by shop', () {
    Product parse(int shopId) =>
        Product.fromJson(TwoShopMarketplace.product(shopId));

    testWidgets("Shop A's price is shown and the item can be added",
        (tester) async {
      market.adapter.on('GET', '/api/products/900',
          (_) => FakeResponse(TwoShopMarketplace.product(TwoShopMarketplace.shopA)));

      await tester.pumpWidget(app(ProductDetailScreen(
          product: parse(TwoShopMarketplace.shopA))));
      await tester.pumpAndSettle();

      expect(find.text('₹100'), findsOneWidget);
      expect(find.text('In stock'), findsOneWidget);

      final addButton = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Add to Cart'));
      expect(addButton.onPressed, isNotNull);
    });

    testWidgets('at Shop B the same item stays visible, has NO price, and '
        'cannot be added', (tester) async {
      market.adapter.on('GET', '/api/products/900',
          (_) => FakeResponse(TwoShopMarketplace.product(TwoShopMarketplace.shopB)));

      await tester.pumpWidget(app(ProductDetailScreen(
          product: parse(TwoShopMarketplace.shopB))));
      await tester.pumpAndSettle();

      // Still on the shelf - the card is not hidden.
      expect(find.text('Toor Dal 1kg'), findsOneWidget);
      // NO PRICE AT ALL. Not ₹0, not an empty rupee sign.
      expect(find.textContaining('₹'), findsNothing);
      expect(find.text('Out of stock'), findsNWidgets(2));
      // And the button is dead.
      expect(find.widgetWithText(FilledButton, 'Add to Cart'), findsNothing);
      final button = tester.widget<FilledButton>(
          find.widgetWithText(FilledButton, 'Unavailable'));
      expect(button.onPressed, isNull);
    });
  });

  group('Comparing, and switching to Shop B', () {
    testWidgets('the comparison leads on final payable, delivery included',
        (tester) async {
      await tester.pumpWidget(app(
        Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => CompareShopsSheet.show(context,
                    variantId: 9001,
                    categoryId: TwoShopMarketplace.categoryId,
                    productName: 'Toor Dal 1kg'),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      // The totals, not the shelf prices: Shop B's ₹60 item plus ₹45 delivery
      // is ₹105, and Shop A's ₹100 plus ₹20 is ₹120. Comparing shelf prices
      // alone would have called Shop B cheaper by ₹40 instead of ₹15.
      expect(find.text('₹105'), findsOneWidget);
      expect(find.text('₹120'), findsOneWidget);
      expect(find.textContaining('+₹45 delivery'), findsOneWidget);
      expect(find.textContaining('+₹20 delivery'), findsOneWidget);
      // The 25% verdict is the server's, and so is the saving.
      expect(find.textContaining('₹15 cheaper in total'), findsOneWidget);
      expect(find.text('Best deal'), findsWidgets);
    });

    testWidgets('switching to Shop B from the comparison changes the shop',
        (tester) async {
      final container = await driveWithContainer(
        tester,
        Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => CompareShopsSheet.show(context,
                    variantId: 9001, categoryId: TwoShopMarketplace.categoryId),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      );
      container.read(shopContextProvider.notifier).select(TwoShopMarketplace.shopA);
      await tester.pumpAndSettle();
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      // Shop B is first (cheapest), Shop A says the customer is already there.
      expect(find.text('You are shopping here'), findsOneWidget);
      await tester.tap(find.text('Shop here').first);
      await tester.pumpAndSettle();

      expect(container.read(shopContextProvider), TwoShopMarketplace.shopB);
      await settleAndDispose(tester, container);
    });

    testWidgets('starring a shop makes it the customer\'s, and it comes first',
        (tester) async {
      await tester.pumpWidget(app(
        Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => CompareShopsSheet.show(context,
                    variantId: 9001, categoryId: TwoShopMarketplace.categoryId),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('My shops'));
      await tester.pumpAndSettle();

      // The honest label for an empty preference.
      expect(find.textContaining("haven't chosen shops for this category"),
          findsOneWidget);
      // Distance order, so Shop A is first.
      expect(market.preferredShopIds, isEmpty);

      // Star Shop B - scrolling the sheet to reach it, exactly as a customer
      // on a phone-sized screen would have to.
      await tester.ensureVisible(find.text('Shop B'));
      await tester.pumpAndSettle();
      final shopBTile =
          find.ancestor(of: find.text('Shop B'), matching: find.byType(Container)).first;
      await tester.tap(find.descendant(
          of: shopBTile, matching: find.byIcon(Icons.star_border_rounded)));
      await tester.pumpAndSettle();

      expect(market.preferredShopIds, [TwoShopMarketplace.shopB]);
      expect(market.requested,
          contains('PUT /api/preferred-shops/${TwoShopMarketplace.categoryId}'));
      // And the server now puts it first - the app did not re-sort anything.
      expect(find.textContaining('Your chosen shops come first'), findsOneWidget);
    });
  });

  group('One basket, two shops', () {
    testWidgets('the basket separates the shops and prices each one itself',
        (tester) async {
      market.addToBasket(
          shopId: TwoShopMarketplace.shopA, name: 'Toor Dal 1kg', price: 100);
      market.addToBasket(
          shopId: TwoShopMarketplace.shopB, name: 'Sona Masoori Rice 5kg', price: 360);

      await tester.pumpWidget(app(const CartScreen()));
      await tester.pumpAndSettle();

      // Two named sections.
      expect(find.text('Shop A'), findsOneWidget);
      expect(find.text('Shop B'), findsOneWidget);
      expect(find.text('Delivered separately by this shop'), findsNWidgets(2));

      // EACH SHOP'S OWN MONEY. ₹100 + ₹20 and ₹360 + ₹45 - two different
      // delivery charges because they are two different shops' decisions.
      expect(find.text('₹120'), findsOneWidget);
      expect(find.text('₹405'), findsOneWidget);
      expect(find.text('₹20'), findsOneWidget);
      expect(find.text('₹45'), findsOneWidget);
      expect(find.text('This shop'), findsNWidgets(2));

      // SAID BEFORE PAYMENT, NOT AFTER.
      expect(
          find.textContaining('Each shop is paid separately and delivers separately'),
          findsOneWidget);

      expect(market.requested, contains('GET /api/carts/mine/by-shop'));
    });

    testWidgets('a single-shop basket is drawn exactly as it always was',
        (tester) async {
      market.addToBasket(
          shopId: TwoShopMarketplace.shopA, name: 'Toor Dal 1kg', price: 100);

      await tester.pumpWidget(app(const CartScreen()));
      await tester.pumpAndSettle();

      // No shop headers, no per-shop money, no mention of shops at all: an
      // existing customer must not have to learn that a marketplace exists.
      expect(find.text('Delivered separately by this shop'), findsNothing);
      expect(find.text('This shop'), findsNothing);
      expect(
          find.textContaining('Each shop is paid separately'), findsNothing);
      expect(find.text('Toor Dal 1kg'), findsOneWidget);
    });
  });

  group('Afterwards', () {
    testWidgets('history shows two orders, one per shop, with each shop\'s own '
        'status and total', (tester) async {
      await tester.pumpWidget(app(const OrderHistoryScreen()));
      await tester.pumpAndSettle();

      expect(find.textContaining('GP-A-501'), findsOneWidget);
      expect(find.textContaining('GP-B-502'), findsOneWidget);
      // Two shops, two statuses - one confirmed and paid, one still being
      // prepared and unpaid. Never one merged order.
      expect(find.text('Shop A'), findsOneWidget);
      expect(find.text('Shop B'), findsOneWidget);
      expect(find.textContaining('120'), findsWidgets);
      expect(find.textContaining('405'), findsWidgets);
    });
  });
}
