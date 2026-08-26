/// Interprets payment-status strings from the backend.
///
/// The backend is authoritative. These helpers only decide what the UI
/// should offer (Pay now vs a paid label). They never mark an order paid.
class PaymentStatusInfo {
  PaymentStatusInfo._();

  /// Money arrived, or COD that is not collected through the gateway.
  static bool isSettled(String? status) {
    switch (normalize(status)) {
      case 'SUCCESS':
      case 'PAID':
      case 'COD_RECEIVED':
      case 'REFUNDED':
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

  static String normalize(String? status) => (status ?? '').trim().toUpperCase();

  static String label(String? status) {
    final value = normalize(status);
    if (value.isEmpty) return 'Unknown';
    return value.replaceAll('_', ' ');
  }
}
