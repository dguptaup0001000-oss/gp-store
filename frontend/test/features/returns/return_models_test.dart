import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/returns/domain/return_models.dart';

/// What the app believes about a return, read from the backend's own shape.
///
/// Worth pinning down because every number here is money or goods: a line
/// total that parses wrong shows a customer the wrong refund, and a status
/// that parses wrong shows them a decision the shop has not made.
void main() {
  Map<String, dynamic> payload({
    String status = 'REQUESTED',
    Object? refundAmount,
    Object? decidedAt,
  }) =>
      {
        'id': 7,
        'orderId': 42,
        'orderNumber': 'GP-42',
        'status': status,
        'reason': 'atta packet was damp',
        'decisionNote': null,
        'refundAmount': refundAmount,
        'requestedAt': '2026-09-01T10:30:00',
        'decidedAt': decidedAt,
        'items': [
          {
            'orderItemId': 11,
            'productName': 'Atta',
            'pack': '1 kg',
            'quantity': 2,
            'unitPrice': 50.5,
            'imageUrl': null,
          },
        ],
      };

  test('a pending return carries no refund figure', () {
    final request = ReturnRequest.fromJson(payload());

    expect(request.id, 7);
    expect(request.isPending, isTrue);
    expect(request.isApproved, isFalse);
    // Nothing has been decided, so there is no amount. Defaulting this to
    // zero would read on screen as "the shop is refunding you nothing".
    expect(request.refundAmount, isNull);
    expect(request.decidedAt, isNull);
  });

  test('an approved return carries what actually went back', () {
    final request = ReturnRequest.fromJson(payload(
      status: 'APPROVED',
      refundAmount: 101,
      decidedAt: '2026-09-02T09:00:00',
    ));

    expect(request.isApproved, isTrue);
    expect(request.refundAmount, 101.0,
        reason: 'an integer from JSON is still money and must not be dropped');
    expect(request.decidedAt, isNotNull);
  });

  test('a line total is the unit price times the quantity', () {
    final line = ReturnRequest.fromJson(payload()).items.single;

    expect(line.orderItemId, 11, reason: 'the LINE, not the product');
    expect(line.quantity, 2);
    expect(line.lineTotal, 101.0);
  });

  test('a malformed or empty payload does not throw', () {
    // A return with no items should render as an empty card, not crash the
    // customer's returns list.
    final request = ReturnRequest.fromJson({'id': 1, 'status': 'CANCELLED'});

    expect(request.items, isEmpty);
    expect(request.orderId, isNull);
    expect(request.status, 'CANCELLED');
  });
}
