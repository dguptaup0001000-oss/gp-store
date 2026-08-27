import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

void main() {
  test('delivery-flat coupons are labelled as delivery off, not cart off', () {
    expect(DiscountType.deliveryFlat.offerLabel(10), 'Up to ₹10 off delivery');
    expect(DiscountType.flat.offerLabel(50), '₹50 OFF');
    expect(DiscountType.percentage.offerLabel(10), '10% OFF');
    expect(DiscountType.deliveryFlat.apiName, 'DELIVERY_FLAT');
  });
}
