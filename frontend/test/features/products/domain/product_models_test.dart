import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

void main() {
  group('Product.primaryVariant', () {
    test('returns null when there are no variants', () {
      const product = Product(id: 1, name: 'Milk', variants: []);
      expect(product.primaryVariant, isNull);
    });

    test('prefers the lowest displayOrder among available variants', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [
          ProductVariant(id: 1, available: true, sellingPrice: 60, displayOrder: 2),
          ProductVariant(id: 2, available: true, sellingPrice: 30, displayOrder: 1),
        ],
      );

      // The lower displayOrder (1) should win regardless of price -
      // displayOrder is the admin's explicit ordering choice, not a
      // cheapest-first heuristic.
      expect(product.primaryVariant?.id, 2);
    });

    test('skips unavailable variants when an available one exists', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [
          ProductVariant(id: 1, available: false, sellingPrice: 30, displayOrder: 1),
          ProductVariant(id: 2, available: true, sellingPrice: 60, displayOrder: 2),
        ],
      );

      // Even though variant 1 has the lower displayOrder, it's out of
      // stock - showing an unavailable variant as the "main" one would be
      // misleading on a product card.
      expect(product.primaryVariant?.id, 2);
    });

    test('falls back to an unavailable variant when ALL variants are unavailable', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [
          ProductVariant(id: 1, available: false, sellingPrice: 30, displayOrder: 2),
          ProductVariant(id: 2, available: false, sellingPrice: 60, displayOrder: 1),
        ],
      );

      // Still needs to show SOMETHING (with an out-of-stock badge, handled
      // by the UI) rather than nothing at all.
      expect(product.primaryVariant, isNotNull);
      expect(product.primaryVariant?.id, 2);
    });
  });

  group('Product.discountPercent', () {
    test('is null when there is no primary variant', () {
      const product = Product(id: 1, name: 'Milk', variants: []);
      expect(product.discountPercent, isNull);
    });

    test('is null when mrp is not set', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [ProductVariant(id: 1, available: true, sellingPrice: 60)],
      );
      expect(product.discountPercent, isNull);
    });

    test('is null when mrp equals selling price (no real discount)', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [ProductVariant(id: 1, available: true, sellingPrice: 60, mrp: 60)],
      );
      expect(product.discountPercent, isNull);
    });

    test('is null when mrp is somehow below selling price (not a discount)', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [ProductVariant(id: 1, available: true, sellingPrice: 60, mrp: 50)],
      );
      expect(product.discountPercent, isNull);
    });

    test('computes the correct rounded percentage off MRP', () {
      const product = Product(
        id: 1,
        name: 'Milk',
        variants: [ProductVariant(id: 1, available: true, sellingPrice: 75, mrp: 100)],
      );
      expect(product.discountPercent, 25);
    });
  });
}
