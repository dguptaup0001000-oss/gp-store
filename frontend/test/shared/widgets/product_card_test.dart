import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/shared/widgets/product_card.dart';

/// What a shopper can and cannot do with a product that is not on the shelf.
///
/// The rule is asymmetric on purpose. Adding an out-of-stock item to the
/// basket is not a thing the shop can honour, so that control is dead AND
/// visibly dead - a disabled button that still looks tappable is worse than
/// no button, because the shopper presses it, nothing happens, and they
/// conclude the app is broken rather than the shelf is empty.
///
/// Saving it for later is the opposite: it is exactly what someone wants at
/// the moment they find the thing they came for is gone, and it is the only
/// action still open to them. So the heart stays live.
void main() {
  Product productWith({required bool available}) => Product(
        id: 1,
        name: 'Aashirvaad Select Atta',
        variants: [
          ProductVariant(
            id: 10,
            quantity: 5,
            unit: 'kg',
            available: available,
            sellingPrice: 285,
            mrp: 320,
          ),
        ],
      );

  Future<void> pump(
    WidgetTester tester, {
    required bool available,
    required double width,
    VoidCallback? onAddPressed,
    VoidCallback? onWishlistToggle,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: width,
              child: ProductCard(
                product: productWith(available: available),
                onAddPressed: onAddPressed,
                onWishlistToggle: onWishlistToggle,
              ),
            ),
          ),
        ),
      ),
    );
  }

  group('out of stock', () {
    testWidgets('the add control is disabled and says so', (tester) async {
      var added = 0;
      await pump(tester,
          available: false, width: 170, onAddPressed: () => added++);

      expect(find.text('Sold out'), findsOneWidget);
      expect(find.text('ADD'), findsNothing);
      expect(find.text('Out of stock'), findsOneWidget);

      final button = tester.widget<OutlinedButton>(find.byType(OutlinedButton));
      // Disabled at the widget level, not merely ignored in the handler -
      // this is what makes Flutter paint it in the disabled colours.
      expect(button.onPressed, isNull);

      await tester.tap(find.byType(OutlinedButton), warnIfMissed: false);
      await tester.pump();
      expect(added, 0, reason: 'a sold-out product must not reach the cart');
    });

    testWidgets('the wishlist heart still works', (tester) async {
      var wishlisted = 0;
      await pump(tester,
          available: false, width: 170, onWishlistToggle: () => wishlisted++);

      await tester.tap(find.byIcon(Icons.favorite_border));
      await tester.pump();
      expect(wishlisted, 1,
          reason: 'saving a sold-out item for later is the whole point');
    });

    testWidgets('the overlay does not swallow taps meant for the heart',
        (tester) async {
      // The "Out of stock" wash covers the image, and the heart sits on top of
      // it. Without IgnorePointer on the wash, hit-testing order decides
      // whether the heart is reachable - which is too subtle to leave untested.
      var wishlisted = 0;
      await pump(tester,
          available: false, width: 110, onWishlistToggle: () => wishlisted++);

      await tester.tap(find.byIcon(Icons.favorite_border));
      await tester.pump();
      expect(wishlisted, 1);
    });
  });

  group('in stock', () {
    testWidgets('the add control is live', (tester) async {
      var added = 0;
      await pump(tester,
          available: true, width: 170, onAddPressed: () => added++);

      expect(find.text('ADD'), findsOneWidget);
      expect(find.text('Out of stock'), findsNothing);

      await tester.tap(find.byType(OutlinedButton));
      await tester.pump();
      expect(added, 1);
    });
  });

  group('density', () {
    // The card picks its layout from its own measured width so that one
    // widget serves the three-up grid, the two-up grid and the carousels.
    // These two tests pin the behaviour that differs between them.

    testWidgets('a roomy card shows the pack size as its own line',
        (tester) async {
      await pump(tester, available: true, width: 170);
      expect(find.text('5 kg'), findsOneWidget);
    });

    testWidgets('a compact card still shows the pack size, on the image',
        (tester) async {
      // Same information, different place - a three-up card has no room for
      // an extra text row, but dropping the pack size entirely would leave
      // the shopper unable to tell 1 kg from 5 kg.
      await pump(tester, available: true, width: 110);
      expect(find.text('5 kg'), findsOneWidget);
    });

    testWidgets('both densities render the price', (tester) async {
      await pump(tester, available: true, width: 110);
      expect(find.text('₹285'), findsOneWidget);
      expect(find.text('₹320'), findsOneWidget);
    });
  });
}
