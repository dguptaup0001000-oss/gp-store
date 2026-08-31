import '../../core/store/store_status.dart';

/// The shop's order switch, as the admin console sees it.
///
/// Plain Dart rather than freezed for the same reason [StoreStatus] is: it
/// composes a StoreStatus, which carries a running Stopwatch, and a generated
/// `copyWith` would happily duplicate that into an object whose countdown
/// started at the wrong moment.
enum StoreOrderAcceptance {
  /// The schedule decides. The normal state, and the one a shop returns to.
  auto,

  /// Forced open, whatever the schedule says.
  on,

  /// Forced closed to NEW ORDERS. Browsing and existing orders are untouched.
  off;

  String get wireName => switch (this) {
        StoreOrderAcceptance.auto => 'AUTO',
        StoreOrderAcceptance.on => 'ON',
        StoreOrderAcceptance.off => 'OFF',
      };

  String get label => switch (this) {
        StoreOrderAcceptance.auto => 'Automatic',
        StoreOrderAcceptance.on => 'Always open',
        StoreOrderAcceptance.off => 'Paused',
      };

  /// What choosing this actually does, in a shopkeeper's terms rather than
  /// the enum's. An operator should not have to guess what "AUTO" means.
  String get explanation => switch (this) {
        StoreOrderAcceptance.auto =>
          'Follow the delivery schedule. Orders are taken around the clock.',
        StoreOrderAcceptance.on =>
          'Keep taking orders even on days marked closed.',
        StoreOrderAcceptance.off =>
          'Stop taking new orders. Customers can still browse.',
      };

  static StoreOrderAcceptance fromWire(String? raw) => switch (raw) {
        'ON' => StoreOrderAcceptance.on,
        'OFF' => StoreOrderAcceptance.off,
        // Anything unrecognised reads as AUTO, the harmless state. A newer
        // server sending a fourth value must not make this build show the
        // shop as paused when it is trading.
        _ => StoreOrderAcceptance.auto,
      };
}

/// A day the vans do not run.
class StoreClosure {
  const StoreClosure({
    required this.id,
    required this.date,
    this.reason,
    this.createdBy,
  });

  final int? id;
  final DateTime date;
  final String? reason;
  final String? createdBy;

  factory StoreClosure.fromJson(Map<String, dynamic> json) => StoreClosure(
        id: (json['id'] as num?)?.toInt(),
        date: DateTime.parse(json['date'] as String),
        reason: json['reason'] as String?,
        createdBy: json['createdBy'] as String?,
      );

  /// YYYY-MM-DD, which is what the delete route addresses a closure by.
  String get wireDate =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';
}

/// Everything the operations card needs, from one request.
class StoreOperations {
  const StoreOperations({
    required this.acceptance,
    required this.status,
    required this.closures,
    this.closureMessage,
    this.updatedBy,
    this.updatedAt,
  });

  final StoreOrderAcceptance acceptance;

  /// What the customer app is being told right now. Shown to the operator so
  /// the console and the shop cannot disagree about whether it is open.
  final StoreStatus status;

  final List<StoreClosure> closures;
  final String? closureMessage;
  final String? updatedBy;
  final String? updatedAt;

  factory StoreOperations.fromJson(Map<String, dynamic> json) {
    return StoreOperations(
      acceptance:
          StoreOrderAcceptance.fromWire(json['orderAcceptance'] as String?),
      status: StoreStatus.fromJson(
          (json['status'] as Map?)?.cast<String, dynamic>() ?? const {}),
      closures: ((json['closures'] as List?) ?? const [])
          .map((e) => StoreClosure.fromJson((e as Map).cast<String, dynamic>()))
          .toList(),
      closureMessage: json['closureMessage'] as String?,
      updatedBy: json['updatedBy'] as String?,
      updatedAt: json['updatedAt'] as String?,
    );
  }
}

/// One day's packing list, already narrowed by the server.
class PreparationList {
  const PreparationList({
    required this.date,
    required this.totalOrders,
    required this.orders,
    this.packingStartsAt,
    this.deliveriesStartAt,
    this.message,
  });

  final DateTime? date;

  /// The TOTAL for the day, not the length of [orders] - the list is one page
  /// of it. Showing the page size as the day's workload would tell whoever is
  /// packing that there are fifty orders when there are three hundred.
  final int totalOrders;

  final List<PreparationOrder> orders;
  final String? packingStartsAt;
  final String? deliveriesStartAt;
  final String? message;

  factory PreparationList.fromJson(Map<String, dynamic> json) {
    final rawDate = json['date'] as String?;
    return PreparationList(
      date: rawDate == null ? null : DateTime.tryParse(rawDate),
      totalOrders: (json['totalOrders'] as num?)?.toInt() ?? 0,
      orders: ((json['orders'] as List?) ?? const [])
          .map((e) =>
              PreparationOrder.fromJson((e as Map).cast<String, dynamic>()))
          .toList(),
      packingStartsAt: json['packingStartsAt'] as String?,
      deliveriesStartAt: json['deliveriesStartAt'] as String?,
      message: json['message'] as String?,
    );
  }
}

class PreparationOrder {
  const PreparationOrder({
    required this.id,
    required this.orderNumber,
    required this.orderStatus,
    required this.totalAmount,
    this.paymentStatus,
    this.deliveryType,
  });

  final int id;
  final String orderNumber;
  final String orderStatus;
  final String? paymentStatus;
  final String? deliveryType;
  final double totalAmount;

  factory PreparationOrder.fromJson(Map<String, dynamic> json) =>
      PreparationOrder(
        id: (json['id'] as num).toInt(),
        orderNumber: json['orderNumber'] as String? ?? '',
        orderStatus: json['orderStatus'] as String? ?? '',
        paymentStatus: json['paymentStatus'] as String?,
        deliveryType: json['deliveryType'] as String?,
        totalAmount: (json['totalAmount'] as num?)?.toDouble() ?? 0,
      );
}

/// One slice of the same-day / next-morning split.
class DeliveryTypeShare {
  const DeliveryTypeShare({
    required this.deliveryType,
    required this.orderCount,
    required this.revenue,
  });

  final String deliveryType;
  final int orderCount;
  final double revenue;

  /// What to put on the chart. UNRECORDED is named plainly rather than hidden:
  /// it is orders placed before the shop had delivery windows, and folding
  /// them into either bucket would make the night trade look either enormous
  /// or non-existent with no way to tell which from the chart.
  String get label => switch (deliveryType) {
        'SAME_DAY' => 'Same-day',
        'NEXT_MORNING' => 'Next morning',
        'MANUAL_SCHEDULED' => 'Scheduled',
        _ => 'Before windows',
      };

  factory DeliveryTypeShare.fromJson(Map<String, dynamic> json) =>
      DeliveryTypeShare(
        deliveryType: json['deliveryType'] as String? ?? 'UNRECORDED',
        orderCount: (json['orderCount'] as num?)?.toInt() ?? 0,
        revenue: (json['revenue'] as num?)?.toDouble() ?? 0,
      );
}
