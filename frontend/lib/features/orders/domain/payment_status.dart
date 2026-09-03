/// Interprets payment-status strings from the backend.
///
/// The backend is authoritative. These helpers only decide what the UI
/// should offer (Pay now vs a paid label). They never mark an order paid.
class PaymentStatusInfo {
  PaymentStatusInfo._();

  /// Money arrived, or COD that is not collected through the gateway.
  ///
  /// A REFUNDED or PARTIALLY_REFUNDED order counts as settled because the
  /// customer DID pay - what happened afterwards does not put them back in
  /// front of a Pay button. Leaving PARTIALLY_REFUNDED out of this list is
  /// not a cosmetic miss: needsOnlineRetry falls through to "anything not
  /// settled is retryable", so the shop app would invite somebody to pay a
  /// second time for an order they had already paid for and been partly
  /// refunded on.
  static bool isSettled(String? status) {
    switch (normalize(status)) {
      case 'SUCCESS':
      case 'PAID':
      case 'COD_RECEIVED':
      case 'REFUNDED':
      case 'PARTIALLY_REFUNDED':
      case 'REFUND_PENDING':
        return true;
      default:
        return false;
    }
  }

  /// Cash-on-delivery: there is nothing to retry in the app.
  static bool isCod(String? status) {
    switch (normalize(status)) {
      case 'COD_PENDING':
      case 'COD_RECEIVED':
        return true;
      default:
        return false;
    }
  }

  /// The order exists and still needs an online payment the customer can retry.
  static bool needsOnlineRetry({
    required String paymentStatus,
    required String orderStatus,
  }) {
    if (orderStatus == 'CANCELLED' || orderStatus == 'DELIVERED') {
      return false;
    }
    if (isSettled(paymentStatus) || isCod(paymentStatus)) {
      return false;
    }
    switch (normalize(paymentStatus)) {
      case 'PENDING':
      case 'FAILED':
      case 'CANCELLED':
      case 'EXPIRED':
      case 'UNPAID':
      case 'ACTIVE':
      case 'UNKNOWN':
        return true;
      default:
        return paymentStatus.trim().isNotEmpty && !isSettled(paymentStatus);
    }
  }

  static String normalize(String? status) =>
      (status ?? '').trim().toUpperCase();

  static String label(String? status) {
    final value = normalize(status);
    if (value.isEmpty) return 'Unknown';
    return value.replaceAll('_', ' ');
  }
}
