import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

void main() {
  Product product({String? model3dUrl}) =>
      Product(id: 1, name: 'Atta 5kg', model3dUrl: model3dUrl);

  group('when the 3D affordance may appear', () {
    test('not for a product with no model - which is almost every product', () {
      // The fallback IS the absence. A grocery catalogue where nothing has a
      // model must look exactly as it did before this feature existed: no
      // button, no empty state, no "3D unavailable" row.
      expect(product().has3dModel, isFalse);
    });

    test('not for a blank url', () {
      // An admin form will produce one of these eventually, and a
      // "View in 3D" button leading to nothing is worse than no button.
      expect(product(model3dUrl: '').has3dModel, isFalse);
      expect(product(model3dUrl: '   ').has3dModel, isFalse);
    });

    test('yes for a real model url', () {
      expect(product(model3dUrl: 'https://cdn.example.com/atta.glb').has3dModel, isTrue);
    });
  });

  group('what a list response carries', () {
    test('a product parsed without the field has no model, and does not throw', () {
      // List endpoints deliberately omit model3dUrl entirely - the backend
      // attaches it on detail only. Parsing must treat absent as "no model"
      // rather than failing, or every feed page would break.
      final parsed = Product.fromJson(const {
        'id': 7,
        'name': 'Toor Dal 1kg',
        'variants': <dynamic>[],
        'active': true,
      });

      expect(parsed.model3dUrl, isNull);
      expect(parsed.has3dModel, isFalse);
    });

    test('an explicit null is the same as absent', () {
      final parsed = Product.fromJson(const {
        'id': 7,
        'name': 'Toor Dal 1kg',
        'model3dUrl': null,
        'variants': <dynamic>[],
        'active': true,
      });

      expect(parsed.has3dModel, isFalse);
    });

    test('a detail response with a model parses it', () {
      final parsed = Product.fromJson(const {
        'id': 7,
        'name': 'Toor Dal 1kg',
        'model3dUrl': 'https://cdn.example.com/dal.glb',
        'variants': <dynamic>[],
        'active': true,
      });

      expect(parsed.has3dModel, isTrue);
      expect(parsed.model3dUrl, 'https://cdn.example.com/dal.glb');
    });
  });
}
