import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/bestseller_models.dart';

/// The "+N more" number.
///
/// The whole risk here is that the count quietly becomes a count of what was
/// DRAWN rather than what EXISTS. The tile always draws four thumbnails, so a
/// number derived from them says the same thing about a shelf of six and a
/// shelf of six hundred - which is exactly the kind of confidently wrong
/// figure a shopper would notice.
void main() {
  BestsellerTile tileFrom(Map<String, dynamic> json) => BestsellerTile.fromJson(json);

  group('BestsellerTile.additionalProductCount', () {
    test('counts the products BEYOND the ones pictured', () {
      final tile = tileFrom({
        'categoryId': 1,
        'categoryName': 'Dairy',
        'productIds': [1, 2, 3, 4],
        'imageUrls': ['a', 'b', 'c', 'd'],
        'productCount': 163,
      });

      expect(tile.additionalProductCount, 159);
    });

    test('says nothing when the collage already shows everything', () {
      final tile = tileFrom({
        'categoryId': 1,
        'categoryName': 'Dairy',
        'productIds': [1, 2, 3, 4],
        'imageUrls': ['a', 'b', 'c', 'd'],
        'productCount': 4,
      });

      expect(tile.additionalProductCount, isNull,
          reason: '"+0 more" under four thumbnails is noise');
    });

    test('says nothing rather than a negative number', () {
      // Should not happen - but a count smaller than the images it came with
      // means the two disagreed, and "+-2 more" is worse than silence.
      final tile = tileFrom({
        'categoryId': 1,
        'categoryName': 'Dairy',
        'productIds': [1, 2, 3, 4],
        'imageUrls': ['a', 'b', 'c', 'd'],
        'productCount': 2,
      });

      expect(tile.additionalProductCount, isNull);
    });

    test('an older backend that omits the count shows no count at all', () {
      final tile = tileFrom({
        'categoryId': 1,
        'categoryName': 'Dairy',
        'productIds': [1, 2],
        'imageUrls': ['a', 'b'],
      });

      expect(tile.productCount, 0);
      expect(tile.additionalProductCount, isNull,
          reason: 'a missing field must not be rendered as a real "+0 more"');
    });

    test('the count is not the number of images', () {
      // The regression this file exists for.
      final tile = tileFrom({
        'categoryId': 1,
        'categoryName': 'Dairy',
        'productIds': [1, 2, 3, 4],
        // Two products have no photo. The category still holds 50.
        'imageUrls': ['a', null, 'c', null],
        'productCount': 50,
      });

      expect(tile.additionalProductCount, 46);
    });
  });
}
