import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

/// How many sizes to tell the customer about.
///
/// A LIST RESPONSE CARRIES ONE VARIANT, NOT ALL OF THEM - the backend trims a
/// card down so a twenty-product page does not serialise a hundred prices no
/// card draws. variantCount is the server saying how many there really are,
/// and optionCount is the single thing the UI reads so no widget has to know
/// which endpoint its product came from.
void main() {
  ProductVariant variant(int id) =>
      ProductVariant(id: id, available: true, sellingPrice: 10);

  test('a trimmed card knows the sizes it was not sent', () {
    final card = Product(
      id: 1,
      name: 'Atta',
      variants: [variant(1)],
      variantCount: 4,
    );
    expect(card.optionCount, 4);
  });

  test('a detail response counts the variants it actually carries', () {
    final detail = Product(
      id: 1,
      name: 'Atta',
      variants: [variant(1), variant(2), variant(3)],
      variantCount: 3,
    );
    expect(detail.optionCount, 3);
  });

  test('a backend that sends no count at all falls back, never to zero', () {
    // An older server, or a Redis entry cached before the field existed,
    // omits the key. Reading 0 there would tell the customer a product they
    // are looking at has no sizes.
    final legacy = Product(
      id: 1,
      name: 'Atta',
      variants: [variant(1), variant(2)],
    );
    expect(legacy.variantCount, 0, reason: 'absent, not one');
    expect(legacy.optionCount, 2, reason: 'the list itself is the fallback');
  });

  test('the count never claims fewer sizes than are in hand', () {
    // Defends against a stale cached count from before a size was added:
    // the variants actually present are the floor.
    final skewed = Product(
      id: 1,
      name: 'Atta',
      variants: [variant(1), variant(2), variant(3)],
      variantCount: 1,
    );
    expect(skewed.optionCount, 3);
  });

  test('a product with no variants offers nothing', () {
    expect(const Product(id: 1, name: 'Atta').optionCount, 0);
  });
}
