import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/checkout/presentation/order_confirmation_screen.dart';
import 'package:gpstore/features/orders/domain/payment_status.dart';

void main() {
  test('incomplete online payment is anything the backend did not settle', () {
    expect(PaymentStatusInfo.isSettled('SUCCESS'), isTrue);
    expect(PaymentStatusInfo.isSettled('PAID'), isTrue);
    expect(PaymentStatusInfo.isSettled('PENDING'), isFalse);
    expect(PaymentStatusInfo.isSettled('FAILED'), isFalse);
    expect(PaymentStatusInfo.isSettled('CANCELLED'), isFalse);
    expect(PaymentStatusInfo.isSettled('UNPAID'), isFalse);
  });

  test('confirmation screen accepts an order id so Pay now can open the order',
      () {
    const screen = OrderConfirmationScreen(
      orderId: 42,
      orderNumber: 'ORD-42',
      paymentMethod: 'ONLINE',
      verifiedPaymentStatus: 'PENDING',
    );
    expect(screen.orderId, 42);
    expect(screen.verifiedPaymentStatus, 'PENDING');
  });
}
