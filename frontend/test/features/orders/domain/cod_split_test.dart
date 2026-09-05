import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/orders/domain/order_models.dart';

/// How a cash-on-delivery order was actually settled at the door.
///
/// THE SHOP'S PROBLEM THIS SERVES. A delivered order kept reading "COD
/// PENDING" because the order's own payment_status column is a second copy
/// written once at checkout and never updated. The server now reports the
/// payment row, and alongside it what the rider wrote down: some customers
/// hand over part in notes and scan the shop's QR for the rest, and the
/// day's cash count has to be reconciled against that split.
void main() {
  OrderDetail detailFrom(Map<String, dynamic> extra) {
    return OrderDetail.fromJson({
      'orderId': 1,
      'orderNumber': 'ORD-1',
      'orderStatus': 'DELIVERED',
      'paymentStatus': 'COD_RECEIVED',
      'orderDate': '2026-01-01T10:00:00',
      'totalAmount': 640,
      ...extra,
    });
  }

  test('a recorded split survives the round trip', () {
    final order = detailFrom({
      'codCashAmount': 400,
      'codUpiAmount': 240,
      'codCollectedAt': '2026-01-01T18:22:00',
    });

    expect(order.hasCodSplit, isTrue);
    expect(order.codCashAmount, 400);
    expect(order.codUpiAmount, 240);
    expect(order.codCollectedAt, '2026-01-01T18:22:00');
  });

  test('all by QR is still a split - cash is zero, not absent', () {
    final order = detailFrom({'codCashAmount': 0, 'codUpiAmount': 640});
    expect(order.hasCodSplit, isTrue);
    expect(order.codCashAmount, 0);
  });

  test('a COD settled without anyone writing it down has no split', () {
    // Marking a delivery delivered settles the payment automatically. That
    // path records no amounts, and rendering "cash ₹0" for it would be a
    // number the day's count cannot be reconciled against.
    final order = detailFrom(const {});
    expect(order.hasCodSplit, isFalse);
    expect(order.codCashAmount, isNull);
    expect(order.codCollectedAt, isNull);
  });

  test('half a split is not a split', () {
    // The server only ever stores the pair. If one arrives without the
    // other, something is wrong and the screen must not print a total that
    // does not add up.
    expect(detailFrom({'codCashAmount': 400}).hasCodSplit, isFalse);
    expect(detailFrom({'codUpiAmount': 240}).hasCodSplit, isFalse);
  });

  test('an older backend that sends none of these still parses', () {
    // The fields are additive: an app build ahead of the server must not
    // throw on their absence, or every order screen breaks at once.
    final order = OrderDetail.fromJson({
      'orderId': 2,
      'orderNumber': 'ORD-2',
      'orderStatus': 'CONFIRMED',
      'paymentStatus': 'COD_PENDING',
      'orderDate': '2026-01-01T10:00:00',
      'totalAmount': 100,
    });
    expect(order.hasCodSplit, isFalse);
    expect(order.paymentStatus, 'COD_PENDING');
  });
}
