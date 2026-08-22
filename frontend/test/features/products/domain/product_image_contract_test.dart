import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

/// The image half of the backend contract, asserted against the JSON the
/// backend actually sends.
///
/// WHY THIS IS A TEST AND NOT A GLANCE AT THE MODEL. The failure mode is
/// silent in both directions. If the backend ever renamed the key to
/// image_url - a one-line Jackson naming-strategy change - or if this model's
/// field were renamed, every product card in the app would draw a placeholder
/// and nothing anywhere would report an error. A placeholder is
/// indistinguishable from a product that genuinely has no photograph, which
/// is precisely why it can go unnoticed for months.
///
/// The payloads below are shaped exactly like ProductResponse/VariantResponse
/// serialize - see ProductImagePipelineTest, which pins the other end.
void main() {
  Map<String, dynamic> variantJson({String? imageUrl}) => {
        'id': 11,
        'quantity': 1.0,
        'unit': 'L',
        'imageUrl': imageUrl,
        'available': true,
        'mrp': 267.0,
        'sellingPrice': 246.0,
        'displayOrder': 0,
      };

  Map<String, dynamic> productJson({String? imageUrl}) => {
        'id': 1,
        'name': 'Vanaspati',
        'brand': 'Gemini',
        'category': {'id': 3, 'name': 'Oils & Ghee', 'active': true},
        'variants': [variantJson(imageUrl: imageUrl)],
        'active': true,
      };

  group('product image contract', () {
    test('the key is imageUrl, and it reaches the variant', () {
      const url = 'https://res.cloudinary.com/demo/image/upload/v1/gp/atta.jpg';

      final product = Product.fromJson(productJson(imageUrl: url));

      expect(product.variants.single.imageUrl, url);
    });

    test('the card reads it through primaryVariant', () {
      // The path the product card actually takes. A variant list that parses
      // correctly is not enough if the getter the card uses returns a
      // different variant.
      const url = 'https://res.cloudinary.com/demo/image/upload/v1/gp/ghee.jpg';

      final product = Product.fromJson(productJson(imageUrl: url));

      expect(product.primaryVariant?.imageUrl, url);
    });

    test('a null image parses as null rather than throwing', () {
      // The commonest case in this catalogue today: a real product with no
      // photograph yet. It must render a card, not an exception.
      final product = Product.fromJson(productJson());

      expect(product.primaryVariant?.imageUrl, isNull);
      expect(product.name, 'Vanaspati');
    });

    test('a MISSING imageUrl key is tolerated, not just a null one', () {
      // An older backend, or a projection that omits the field entirely.
      // json_serializable treats absent and null alike for a nullable field,
      // which is asserted rather than assumed: a non-nullable field here
      // would throw and take the whole product list down with it.
      final json = productJson();
      (json['variants'] as List).first.remove('imageUrl');

      final product = Product.fromJson(json);

      expect(product.primaryVariant?.imageUrl, isNull);
    });

    test('a product with no variants at all does not crash the card', () {
      final json = productJson()..['variants'] = <Map<String, dynamic>>[];

      final product = Product.fromJson(json);

      expect(product.primaryVariant, isNull,
          reason: 'the card must be able to ask and get nothing back');
    });

    test('category images use the same key', () {
      const url = 'https://res.cloudinary.com/demo/image/upload/v1/gp/oils.jpg';

      final category = Category.fromJson({
        'id': 3,
        'name': 'Oils & Ghee',
        'imageUrl': url,
        'active': true,
      });

      expect(category.imageUrl, url);
    });
  });
}
