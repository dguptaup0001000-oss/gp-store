import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/shared/widgets/product_card.dart';

/// A product that comes in more than one pack size.
///
/// THE BUG THESE PIN. A browse, search or feed card carries exactly ONE
/// variant - the backend trims the rest so a twenty-product page does not
/// serialise a hundred prices no card draws. So a card for a product sold in
/// 500 g and 1 kg looked identical to a one-size product, and ADD put
/// whichever size the server picked into the basket. The customer had no way
/// to know the other size existed.
///
/// variantCount is the server telling the card how many sizes exist without
/// sending them, and onOptionsPressed is what the card does with that.
void main() {
  Product multiSize({int variantCount = 3, bool available = true}) => Product(
        id: 1,
        name: 'Aashirvaad Select Atta',
        // One variant in hand, three in the shop - exactly what a trimmed
        // card response looks like.
        variantCount: variantCount,
        variants: [
          ProductVariant(
            id: 10,
            quantity: 1,
            unit: 'kg',
            available: available,
            sellingPrice: 62,
            mrp: 70,
          ),
        ],
      );

  Future<void> pump(
    WidgetTester tester, {
    required Widget card,
    double width = 170,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(child: SizedBox(width: width, child: card)),
        ),
      ),
    );
  }

  testWidgets('the card says how many sizes there are', (tester) async {
    await pump(
      tester,
      card: ProductCard(
        product: multiSize(),
        onOptionsPressed: () {},
      ),
    );

    // Beside the pack size on the line that already existed - the card's
    // height is derived arithmetic, so this must not become its own row.
    expect(find.text('1 kg · 3 options'), findsOneWidget);
  });

  testWidgets('a one-size product says nothing about options', (tester) async {
    await pump(
      tester,
      card: ProductCard(product: multiSize(variantCount: 1)),
    );

    expect(find.text('1 kg'), findsOneWidget);
    expect(find.textContaining('options'), findsNothing);
  });

  testWidgets('ADD opens the chooser instead of adding a size nobody picked',
      (tester) async {
    var opened = 0;
    var addedDirectly = 0;

    await pump(
      tester,
      card: ProductCard(
        product: multiSize(),
        onAddPressed: () => addedDirectly++,
        onOptionsPressed: () => opened++,
      ),
    );

    await tester.tap(find.text('ADD'));
    await tester.pump();

    expect(opened, 1);
    expect(addedDirectly, 0,
        reason: 'adding straight to the basket is what skipped the choice');
  });

  testWidgets('what is already in the basket is shown, not a stepper',
      (tester) async {
    // A single +/- on the card can only mean ONE size. With two sizes in the
    // basket it would either be wrong about the count or increment a size the
    // customer never chose, so the count is shown and the tap reopens the
    // chooser, where each size has its own control.
    var opened = 0;
    await pump(
      tester,
      card: ProductCard(
        product: multiSize(),
        quantityInCart: 3,
        onOptionsPressed: () => opened++,
      ),
    );

    expect(find.text('3 IN BAG'), findsOneWidget);
    expect(find.byIcon(Icons.add), findsNothing);
    expect(find.byIcon(Icons.remove), findsNothing);

    await tester.tap(find.text('3 IN BAG'));
    await tester.pump();
    expect(opened, 1);
  });

  testWidgets('a sold-out multi-size product still refuses the tap',
      (tester) async {
    var opened = 0;
    await pump(
      tester,
      card: ProductCard(
        product: multiSize(available: false),
        onOptionsPressed: () => opened++,
      ),
    );

    // Twice: the overlay across the photo, and the button's own label.
    expect(find.text('Sold out'), findsNWidgets(2));

    final button = tester.widget<OutlinedButton>(find.byType(OutlinedButton));
    expect(button.onPressed, isNull,
        reason: 'a dead control must be visibly dead, not silently dead');
    expect(opened, 0);
  });

  testWidgets('a single-size product keeps its stepper', (tester) async {
    // The old behaviour must be untouched: onOptionsPressed is null there,
    // and the +/- on the card is unambiguous because there is one size.
    await pump(
      tester,
      card: ProductCard(
        product: multiSize(variantCount: 1),
        quantityInCart: 2,
        onIncrement: () {},
        onDecrement: () {},
      ),
    );

    expect(find.byIcon(Icons.add), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
  });
}
