import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/orders/domain/payment_status.dart';

void main() {
  group('PaymentStatusInfo', () {
    test('treats SUCCESS and PAID as settled', () {
      expect(PaymentStatusInfo.isSettled('SUCCESS'), isTrue);
      expect(PaymentStatusInfo.isSettled('PAID'), isTrue);
      expect(PaymentStatusInfo.isSettled('paid'), isTrue);
    });

    test('does not treat pending/failed/cancelled/unpaid as settled', () {
      for (final status in [
        'PENDING',
        'FAILED',
        'CANCELLED',
        'EXPIRED',
        'UNPAID',
        'ACTIVE'
      ]) {
        expect(PaymentStatusInfo.isSettled(status), isFalse, reason: status);
      }
    });

    test(
        'needsOnlineRetry covers the recovery states without losing COD orders',
        () {
      expect(
        PaymentStatusInfo.needsOnlineRetry(
            paymentStatus: 'PENDING', orderStatus: 'PENDING_CONFIRMATION'),
        isTrue,
      );
      expect(
        PaymentStatusInfo.needsOnlineRetry(
            paymentStatus: 'FAILED', orderStatus: 'PENDING_CONFIRMATION'),
        isTrue,
      );
      expect(
        PaymentStatusInfo.needsOnlineRetry(
            paymentStatus: 'UNPAID', orderStatus: 'PENDING_CONFIRMATION'),
        isTrue,
      );
      expect(
        PaymentStatusInfo.needsOnlineRetry(
            paymentStatus: 'COD_PENDING', orderStatus: 'CONFIRMED'),
        isFalse,
      );
      expect(
        PaymentStatusInfo.needsOnlineRetry(
            paymentStatus: 'SUCCESS', orderStatus: 'CONFIRMED'),
        isFalse,
      );
    });
  });
}
