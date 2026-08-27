/// Pure poll bookkeeping for the shop-counter soundbox.
///
/// The first response arms the high-water mark and must not be announced:
/// opening the admin app should not speak every order already in the shop.
class AdminOrderSoundPoll {
  int? afterId;
  bool _armed = false;

  bool get armed => _armed;

  void reset() {
    afterId = null;
    _armed = false;
  }

  /// [firstCall] is the arming GET with no afterId. Later calls announce
  /// [orders] and store [responseAfterId] for the next poll.
  List<AdminNewOrderAlert> ingest({
    required int responseAfterId,
    required List<AdminNewOrderAlert> orders,
    required bool firstCall,
  }) {
    afterId = responseAfterId;
    if (firstCall || !_armed) {
      _armed = true;
      return const [];
    }
    return orders;
  }
}

class AdminNewOrderAlert {
  const AdminNewOrderAlert({
    required this.orderId,
    required this.customerName,
    required this.orderAmount,
  });

  final String orderId;
  final String customerName;
  final String orderAmount;

  factory AdminNewOrderAlert.fromJson(Map<String, dynamic> json) {
    return AdminNewOrderAlert(
      orderId: '${json['orderId']}',
      customerName: (json['customerName'] as String?) ?? 'a customer',
      orderAmount: (json['orderAmount'] as String?) ?? '0',
    );
  }
}
