import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/checkout/domain/checkout_models.dart';
import 'package:gpstore/features/checkout/domain/orders_to_pay.dart';

/// A TWO-SHOP BASKET OWES TWO SHOPS.
///
/// THE BUG. The checkout screen paid `PlaceOrderResult.orderId`, which is the
/// first shop's order and nothing else, and then showed a confirmation
/// reading "2 orders placed". A customer with a basket from two kiranas paid
/// one of them, was told everything was done, and the second shop's order sat
/// unpaid until it timed out - at which point the stale-payment sweep
/// cancelled it. Nothing in the app or its tests would have said a word.
///
/// The rule now lives in one function so it can be checked without a payment
/// gateway, which is the only reason it went unchecked for so long.
void main() {
  PlacedShopOrder shopOrder(int orderId, int shopId) =>
      PlacedShopOrder(orderId: orderId, shopId: shopId);

  group('what the app must charge', () {
    test('a two-shop checkout pays both shops, in the server\'s order', () {
      const result = PlaceOrderResult(
        success: true,
        orderId: 501,
        shopOrders: [
          PlacedShopOrder(orderId: 501, shopId: 11),
          PlacedShopOrder(orderId: 502, shopId: 22),
        ],
      );

      expect(ordersToPay(result), [501, 502]);
    });

    test('three shops means three separate payments, never one', () {
      final result = PlaceOrderResult(
        success: true,
        orderId: 901,
        shopOrders: [shopOrder(901, 11), shopOrder(902, 22), shopOrder(903, 33)],
      );

      // A LIST, NOT A TOTAL. Each amount is owed to a different merchant, and
      // a single combined charge is precisely what the payment boundary
      // exists to prevent.
      expect(ordersToPay(result), hasLength(3));
      expect(ordersToPay(result), [901, 902, 903]);
    });

    test('the first shop is not the only shop', () {
      const result = PlaceOrderResult(
        success: true,
        orderId: 501,
        shopOrders: [
          PlacedShopOrder(orderId: 501, shopId: 11),
          PlacedShopOrder(orderId: 502, shopId: 22),
        ],
      );

      // The regression, stated as its own assertion so a future change that
      // reintroduces it fails with the right sentence attached.
      expect(ordersToPay(result), isNot([501]),
          reason: 'paying only orderId leaves the second shop unpaid while '
              'the confirmation screen says two orders were placed');
    });
  });

  group('the single-shop path is untouched', () {
    test('one shop order is one payment', () {
      const result = PlaceOrderResult(
        success: true,
        orderId: 77,
        shopOrders: [PlacedShopOrder(orderId: 77, shopId: 1)],
      );

      expect(ordersToPay(result), [77]);
    });

    test('a backend that sends no breakdown still gets its one order paid', () {
      // The shipped single-shop path, and the one that must never break: an
      // older backend returns the order it made and no per-shop list.
      const result = PlaceOrderResult(success: true, orderId: 77);

      expect(ordersToPay(result), [77]);
    });

    test('nothing to pay when nothing was placed', () {
      const result = PlaceOrderResult(success: false);

      expect(ordersToPay(result), isEmpty);
    });
  });
}
