import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/nearby_shop_page.dart';

void main() {
  test('parses the paginated nearby shops HTTP envelope and storefront DTO', () {
    final page = NearbyShopPage.fromJson({
      'page': 1,
      'size': 20,
      'totalElements': 43,
      'totalPages': 3,
      'hasNext': true,
      'shops': [
        {
          'shopId': 902,
          'displayName': 'Sharma Grocery TEST',
          'distanceKm': 2.4,
          'deliversHere': true,
          'acceptingOrders': false,
          'openNow': true,
        },
      ],
    });

    expect(page.page, 1);
    expect(page.totalElements, 43);
    expect(page.hasNext, isTrue);
    expect(page.shops.single.shopId, 902);
    expect(page.shops.single.displayName, 'Sharma Grocery TEST');
    expect(page.shops.single.distanceKm, 2.4);
    expect(page.shops.single.acceptingOrders, isFalse);
  });
}
