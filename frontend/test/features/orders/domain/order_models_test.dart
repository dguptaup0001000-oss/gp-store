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

  group('needsOnlinePayment', () {
    OrderDetail detail({required String orderStatus, required String paymentStatus}) {
      return OrderDetail(
        orderId: 1,
        orderNumber: 'ORD-1',
        orderStatus: orderStatus,
        paymentStatus: paymentStatus,
        orderDate: '2026-01-01T10:00:00',
        totalAmount: 100,
      );
    }

    OrderSummary summary({required String orderStatus, required String paymentStatus}) {
      return OrderSummary(
        orderId: 1,
        orderNumber: 'ORD-1',
        totalAmount: 100,
        orderStatus: orderStatus,
        paymentStatus: paymentStatus,
        orderDate: '2026-01-01T10:00:00',
      );
    }

    test('is true for unpaid online states so Pay now can show', () {
      for (final status in ['PENDING', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNPAID', 'ACTIVE']) {
        expect(
          detail(orderStatus: 'PENDING_CONFIRMATION', paymentStatus: status).needsOnlinePayment,
          isTrue,
          reason: status,
        );
        expect(
          summary(orderStatus: 'PENDING_CONFIRMATION', paymentStatus: status).needsOnlinePayment,
          isTrue,
          reason: status,
        );
      }
    });

    test('is false once paid, COD, refunded, delivered or order-cancelled', () {
      expect(detail(orderStatus: 'CONFIRMED', paymentStatus: 'SUCCESS').needsOnlinePayment, isFalse);
      expect(detail(orderStatus: 'CONFIRMED', paymentStatus: 'PAID').needsOnlinePayment, isFalse);
      expect(detail(orderStatus: 'CONFIRMED', paymentStatus: 'COD_PENDING').needsOnlinePayment, isFalse);
      expect(detail(orderStatus: 'CANCELLED', paymentStatus: 'PENDING').needsOnlinePayment, isFalse);
      expect(detail(orderStatus: 'DELIVERED', paymentStatus: 'PENDING').needsOnlinePayment, isFalse);
    });
  });
}
