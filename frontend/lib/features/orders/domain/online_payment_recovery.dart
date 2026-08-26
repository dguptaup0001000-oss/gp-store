import '../../checkout/data/cashfree_checkout_service.dart';
import '../../checkout/data/checkout_repository.dart';
import 'payment_status.dart';

/// Runs one online-payment attempt for an order that already exists.
///
/// The order is never created here. Checkout creates it first; this is the
/// recovery path when that payment did not finish. Duplicate attempts are
/// refused by the backend if the order is already paid.
class OnlinePaymentRecovery {
  OnlinePaymentRecovery({
    required this.repository,
    CashfreeCheckoutService? gateway,
  }) : _gateway = gateway ?? CashfreeCheckoutService();

  final CheckoutRepository repository;
  final CashfreeCheckoutService _gateway;

  /// Backend verdict (PaymentStatus name), never the SDK's.
  Future<String> pay({
    required int orderId,
    void Function(String phase)? onPhase,
  }) async {
    onPhase?.call('preparing');
    final checkout = await repository.startCheckoutSession(orderId: orderId);

    onPhase?.call('gateway');
    final outcome = await _gateway.open(
      orderId: checkout.providerOrderId,
      paymentSessionId: checkout.paymentSessionId,
      production: checkout.production,
    );

    if (outcome == CheckoutOutcome.couldNotOpen) {
      throw Exception('Could not open the payment screen. Please try again.');
    }

    onPhase?.call('verifying');
    return repository.verifyPayment(orderId: orderId);
  }

  static bool succeeded(String? backendStatus) =>
      PaymentStatusInfo.isSettled(backendStatus);
}
