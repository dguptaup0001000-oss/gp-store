import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

/// THE CONTRACT THAT BROKE THE APP ONCE.
///
/// Part 2 §10 says a shop that lists an item and has run out keeps the card
/// on the shelf, says "Out of stock", disables Add - and HIDES THE PRICE. The
/// server enforces the last part by WITHHOLDING the number rather than
/// trusting each client to decline to draw it, so `sellingPrice` arrives as
/// null for exactly those variants.
///
/// `ProductVariant.sellingPrice` was `required double` when that landed. The
/// mismatch was not cosmetic: json_serializable throws on a null for a
/// non-nullable num, so THE ENTIRE PRODUCT LIST FAILED TO PARSE the moment
/// any one variant in it was out of stock - which is a normal Tuesday for a
/// kirana. The whole screen, not one card.
///
/// It was caught by an audit rather than by a test, which is the reason this
/// file exists: the type and the server's behaviour have to be pinned
/// together, or the next person to "tidy up" the model by making the field
/// required again gets a green suite and a broken app.
void main() {
  group('a variant the shop has run out of', () {
    test('parses when the server sends no price', () {
      final variant = ProductVariant.fromJson(const {
        'id': 7,
        'quantity': 1.0,
        'unit': 'kg',
        'available': true,
        'inStock': false,
        'sellingPrice': null,
        'mrp': null,
      });

      expect(variant.sellingPrice, isNull);
      expect(variant.hasPrice, isFalse,
          reason: 'hasPrice is what every price widget asks before drawing a '
              'rupee sign, so it has to be false here');
      expect(variant.isBuyable, isFalse);
    });

    test('parses when the server omits the price field entirely', () {
      final variant = ProductVariant.fromJson(const {
        'id': 7,
        'available': true,
        'inStock': false,
      });

      expect(variant.hasPrice, isFalse);
    });

    test('carries no discount badge', () {
      final product = Product.fromJson(const {
        'id': 1,
        'name': 'Atta',
        'variants': [
          {
            'id': 7,
            'available': true,
            'inStock': false,
            'sellingPrice': null,
            'mrp': 60.0,
          }
        ],
      });

      expect(product.discountPercent, isNull,
          reason: '"40% off" over a card that cannot be bought is an '
              'advertisement for nothing - and computing it would have to '
              'force-unwrap a null price to do the arithmetic');
    });
  });

  group('a variant the shop is holding', () {
    test('keeps its price and its discount', () {
      final product = Product.fromJson(const {
        'id': 1,
        'name': 'Atta',
        'variants': [
          {
            'id': 7,
            'available': true,
            'inStock': true,
            'sellingPrice': 45.0,
            'mrp': 60.0,
          }
        ],
      });

      final variant = product.variants.single;
      expect(variant.hasPrice, isTrue);
      expect(variant.sellingPrice, 45.0);
      expect(variant.isBuyable, isTrue);
      expect(product.discountPercent, 25);
    });

    test('a response with no stock information keeps its price', () {
      // An admin catalogue screen and a platform report have no shop whose
      // stock the number could mean. Reading a missing inStock as false would
      // blank the merchant's own product list.
      final variant = ProductVariant.fromJson(const {
        'id': 7,
        'available': true,
        'sellingPrice': 45.0,
      });

      expect(variant.inStock, isNull);
      expect(variant.hasPrice, isTrue);
      expect(variant.isBuyable, isTrue,
          reason: 'null stock means "the server did not say", not "there is none"');
    });
  });
}
