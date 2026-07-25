import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/orders/domain/order_models.dart';

void main() {
  group('OrderDetail.isCancellable', () {
    OrderDetail orderWithStatus(String status) {
      return OrderDetail(
        orderId: 1,
        orderNumber: 'ORD-1',
        orderStatus: status,
        paymentStatus: 'PENDING',
        orderDate: '2026-01-01T10:00:00',
        totalAmount: 100,
      );
    }

    test('is true for an order still in progress', () {
      expect(orderWithStatus('PENDING_CONFIRMATION').isCancellable, isTrue);
      expect(orderWithStatus('CONFIRMED').isCancellable, isTrue);
      expect(orderWithStatus('PACKED').isCancellable, isTrue);
      expect(orderWithStatus('OUT_FOR_DELIVERY').isCancellable, isTrue);
    });

    test('is false once delivered - mirrors the backend rejecting this transition', () {
      expect(orderWithStatus('DELIVERED').isCancellable, isFalse);
    });

    test('is false once already cancelled - cannot cancel twice', () {
      expect(orderWithStatus('CANCELLED').isCancellable, isFalse);
    });
  });
}
