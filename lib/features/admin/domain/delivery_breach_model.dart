/// Deliberately NOT a freezed/json_serializable model - the backend's
/// GET /api/deliveries/breached returns the raw Delivery entity, whose
/// nested "order" field is the RAW Order entity shape (orderItems, address,
/// etc.) - completely different from our OrderDetail/OrderSummary DTOs used
/// elsewhere in the app. Rather than force-fit a mismatched model, this
/// just reaches into the JSON for the handful of fields an admin actually
/// needs to review a breach.
class DeliveryBreach {
  const DeliveryBreach({
    required this.id,
    this.orderId,
    this.orderNumber,
    this.deliveryStatus,
    this.estimatedDeliveryTime,
    this.deliveredAt,
    this.deliveryPersonName,
    this.assignedAt,
  });

  final int id;
  final int? orderId;
  final String? orderNumber;
  final String? deliveryStatus;
  final String? estimatedDeliveryTime;
  final String? deliveredAt;
  final String? deliveryPersonName;
  final String? assignedAt;

  factory DeliveryBreach.fromJson(Map<String, dynamic> json) {
    final order = json['order'] as Map<String, dynamic>?;

    return DeliveryBreach(
      id: json['id'] as int,
      orderId: order?['id'] as int?,
      orderNumber: order?['orderNumber'] as String?,
      deliveryStatus: json['deliveryStatus'] as String?,
      estimatedDeliveryTime: json['estimatedDeliveryTime'] as String?,
      deliveredAt: json['deliveredAt'] as String?,
      deliveryPersonName: json['deliveryPersonName'] as String?,
      assignedAt: json['assignedAt'] as String?,
    );
  }
}
