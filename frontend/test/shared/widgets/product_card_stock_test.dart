import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';
import 'package:gpstore/shared/widgets/product_card.dart';

/// §7 STATE 2: THE SHOP SELLS IT, AND HAS RUN OUT.
///
/// The sibling of product_card_test, which covers the item this shop does not
/// list at all. This one is the case between the two, and it is the ordinary
/// state of a kirana at the end of a Sunday: the line is on the shelf, the
/// shelf is bare.
///
/// WHAT USED TO HAPPEN. The card read `available` - the shop's LISTING flag,
/// "we sell this" - and nothing ever told it about stock, so a sold-out size
/// was drawn with its price and a live ADD button. The server refused the add
/// (it always has), so the customer pressed a button that did nothing and drew
/// the obvious conclusion about the app.
///
/// THE PRICE GOES TOO. A price with no way to buy at it is an offer the shop
/// cannot honour, and it is the number a customer remembers and compares
/// against the shop next door.
void main() {
  Product atta({required bool? inStock, bool available = true}) => Product(
        id: 1,
        name: 'Aashirvaad Select Atta',
        variants: [
          ProductVariant(
            id: 10,
            quantity: 5,
            unit: 'kg',
            available: available,
            inStock: inStock,
            sellingPrice: 285,
            mrp: 320,
          ),
        ],
      );

  Future<void> pump(WidgetTester tester, Product product,
      {VoidCallback? onAddPressed}) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 170,
              child: ProductCard(product: product, onAddPressed: onAddPressed),
            ),
          ),
        ),
      ),
    );
  }

  testWidgets('a listed item with no stock reads as out of stock', (tester) async {
    var added = 0;
    await pump(tester, atta(inStock: false), onAddPressed: () => added++);

    expect(find.text('Out of stock'), findsOneWidget,
        reason: 'THE SHOP SELLS THIS AND HAS RUN OUT. Drawing a live ADD button here is '
            'inviting an action the server has always refused.');
    expect(find.text('ADD'), findsNothing);
    expect(find.textContaining('285'), findsNothing,
        reason: 'a price nobody can buy at is an offer the shop cannot honour');

    await tester.tap(find.byType(OutlinedButton), warnIfMissed: false);
    await tester.pump();
    expect(added, 0);
  });

  testWidgets('a listed item WITH stock is unchanged', (tester) async {
    await pump(tester, atta(inStock: true), onAddPressed: () {});

    expect(find.text('Out of stock'), findsNothing);
    expect(find.text('ADD'), findsOneWidget);
    expect(find.textContaining('285'), findsOneWidget);
  });

  testWidgets('a server that says nothing about stock is trusted', (tester) async {
    // NULL IS NOT ZERO. An older backend, and any response built without a
    // shop whose stock it could mean, leaves the field off - and reading that
    // as "out of stock" would grey out the entire catalogue over a missing
    // key. The card has to keep working exactly as it did before the field
    // existed (§19).
    await pump(tester, atta(inStock: null), onAddPressed: () {});

    expect(find.text('ADD'), findsOneWidget);
    expect(find.textContaining('285'), findsOneWidget);
    expect(find.text('Out of stock'), findsNothing);
  });

  testWidgets('not listed still beats in stock', (tester) async {
    // A shop can hold an item it has delisted; it is still not for sale.
    await pump(tester, atta(inStock: true, available: false), onAddPressed: () {});
    expect(find.text('Out of stock'), findsOneWidget);
  });

  test('the card offers the cheapest size the customer can actually buy', () {
    // The backend picks the same way (ProductResponse.fromCard); this is the
    // app agreeing rather than trusting, because primaryVariant is what every
    // grid, carousel and wishlist row renders.
    final product = Product(
      id: 1,
      name: 'Atta',
      variants: [
        const ProductVariant(
            id: 1, quantity: 1, unit: 'kg', available: true, inStock: false,
            sellingPrice: 60, displayOrder: 1),
        const ProductVariant(
            id: 2, quantity: 5, unit: 'kg', available: true, inStock: true,
            sellingPrice: 280, displayOrder: 2),
      ],
    );

    expect(product.primaryVariant?.id, 2,
        reason: 'AN EMPTY SIZE MUST NOT TAKE THE SIZES BESIDE IT DOWN WITH IT (§7). '
            'Leading with the cheaper empty shelf gives the customer a card they '
            'cannot tap for a product the shop is selling.');
  });

  test('a wholly sold-out product still renders a variant', () {
    final product = Product(
      id: 1,
      name: 'Atta',
      variants: [
        const ProductVariant(
            id: 1, quantity: 1, unit: 'kg', available: true, inStock: false,
            sellingPrice: 60),
      ],
    );
    expect(product.primaryVariant?.id, 1,
        reason: 'the card still has to appear, saying it is out of stock');
  });
}
