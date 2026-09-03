/// A return request, as either side of the counter sees it.
///
/// NOTABLY ABSENT FROM THE REQUEST SIDE: an amount. The refund is worked out
/// by the backend from the order's own stored line prices, because the money
/// is the shop's and this app is a phone. There is nowhere here to put a
/// figure, which is the strongest form of that rule.
class ReturnRequest {
  const ReturnRequest({
    required this.id,
    required this.orderId,
    this.orderNumber,
    required this.status,
    this.reason,
    this.decisionNote,
    this.refundAmount,
    this.requestedAt,
    this.decidedAt,
    required this.items,
  });

  final int id;
  final int? orderId;
  final String? orderNumber;

  /// REQUESTED, APPROVED, REJECTED, CANCELLED.
  final String status;

  /// The customer's own words.
  final String? reason;

  /// The shop's answer. Shown to the customer on a refusal.
  final String? decisionNote;

  /// What was actually refunded. Null until approved.
  final double? refundAmount;

  final DateTime? requestedAt;
  final DateTime? decidedAt;
  final List<ReturnLine> items;

  bool get isPending => status == 'REQUESTED';
  bool get isApproved => status == 'APPROVED';
  bool get isRejected => status == 'REJECTED';

  factory ReturnRequest.fromJson(Map<String, dynamic> json) {
    final lines = json['items'] as List? ?? const [];
    final requested = json['requestedAt'] as String?;
    final decided = json['decidedAt'] as String?;
    return ReturnRequest(
      id: json['id'] as int,
      orderId: (json['orderId'] as num?)?.toInt(),
      orderNumber: json['orderNumber'] as String?,
      status: json['status'] as String? ?? 'REQUESTED',
      reason: json['reason'] as String?,
      decisionNote: json['decisionNote'] as String?,
      refundAmount: (json['refundAmount'] as num?)?.toDouble(),
      requestedAt: requested == null ? null : DateTime.tryParse(requested),
      decidedAt: decided == null ? null : DateTime.tryParse(decided),
      items: lines
          .map((e) => ReturnLine.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

class ReturnLine {
  const ReturnLine({
    required this.orderItemId,
    required this.productName,
    this.pack,
    required this.quantity,
    required this.unitPrice,
    this.imageUrl,
  });

  final int? orderItemId;
  final String productName;
  final String? pack;
  final int quantity;
  final double unitPrice;
  final String? imageUrl;

  double get lineTotal => unitPrice * quantity;

  factory ReturnLine.fromJson(Map<String, dynamic> json) {
    return ReturnLine(
      orderItemId: (json['orderItemId'] as num?)?.toInt(),
      productName: json['productName'] as String? ?? 'Item',
      pack: json['pack'] as String?,
      quantity: (json['quantity'] as num?)?.toInt() ?? 0,
      unitPrice: (json['unitPrice'] as num?)?.toDouble() ?? 0,
      imageUrl: json['imageUrl'] as String?,
    );
  }
}
